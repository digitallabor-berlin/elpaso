package dev.digitallabor.elpaso.wallet.vct

import android.util.Base64
import dev.digitallabor.elpaso.wallet.util.B64u
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reconstructs the fully-disclosed claims tree of an SD-JWT VC, resolving `_sd` digests
 * against the trailing disclosures so nested claims (e.g. `address.locality`) land at
 * their real path rather than being flattened to the top level.
 *
 * Why this exists: [SdJwtDisclosureScanner] enumerates raw disclosures one by one and is
 * the right tool for listing what's disclosable. But the DC API matcher walks DCQL paths
 * against a tree (`select_nodes`), so it needs the disclosures grafted back into the
 * payload at the position the issuer signed them at. Without that, a verifier asking for
 * `["address","locality"]` sees no candidate even though we hold the data.
 *
 * Reference: SD-JWT VC draft-ietf-oauth-sd-jwt-vc-09 §5 (claim hashing / selective
 * disclosure) and draft-ietf-oauth-selective-disclosure-jwt-12 §4 (the `_sd` / `...`
 * placeholders).
 */
object SdJwtClaimsReconstructor {

    private const val SD_KEY = "_sd"
    private const val SD_ALG_KEY = "_sd_alg"
    private const val ARRAY_DISCLOSURE_KEY = "..."

    /**
     * Housekeeping fields from the issuer JWT payload that aren't application claims and
     * would only pollute the registered package JSON. The matcher never queries these, but
     * the system selector might surface unknown extras as fields, so trim explicitly.
     */
    private val HOUSEKEEPING = setOf(
        SD_KEY, SD_ALG_KEY,
        "vct", "vct#integrity",
        "iss", "sub", "aud", "iat", "nbf", "exp", "jti",
        "cnf", "status",
    )

    private sealed interface DisclosureEntry {
        data class ObjectProperty(val name: String, val value: JsonElement) : DisclosureEntry
        data class ArrayElement(val value: JsonElement) : DisclosureEntry
    }

    /**
     * Returns the disclosable claims tree for [payload]. On any parse failure (malformed
     * SD-JWT, unsupported `_sd_alg`, broken disclosure) returns an empty object so the
     * caller can keep going — the matcher will simply find no matches for this credential.
     */
    fun reconstruct(payload: ByteArray): JsonObject = runCatching {
        val sdJwt = payload.decodeToString()
        // Disclosures are `~`-separated tokens after the JWT; the final empty token (when
        // the payload ends with `~`) is allowed and ignored. A trailing KB-JWT contains
        // dots and won't decode as a JSON array, so it falls through harmlessly below.
        val parts = sdJwt.split('~')
        val jwt = parts.first()
        val disclosureTokens = parts.drop(1).filter { it.isNotEmpty() }

        val payloadSegment = jwt.split('.').getOrNull(1) ?: return JsonObject(emptyMap())
        val payloadJson = Json.parseToJsonElement(B64u.decode(payloadSegment).decodeToString())
            .jsonObject

        val sdAlg = payloadJson[SD_ALG_KEY]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: "sha-256"
        val digestAlg = digestAlgFromName(sdAlg) ?: return JsonObject(emptyMap())

        val digestIndex = mutableMapOf<String, DisclosureEntry>()
        for (token in disclosureTokens) {
            val entry = decodeDisclosure(token) ?: continue
            digestIndex[computeDigest(token, digestAlg)] = entry
        }

        val resolved = resolveElement(payloadJson, digestIndex) as? JsonObject
            ?: return JsonObject(emptyMap())
        // Drop housekeeping AFTER resolution so digests that happened to expose claims
        // named after housekeeping fields (unusual but legal) survive.
        JsonObject(resolved.filterKeys { it !in HOUSEKEEPING })
    }.getOrDefault(JsonObject(emptyMap()))

    private fun decodeDisclosure(token: String): DisclosureEntry? = runCatching {
        val json = B64u.decode(token).decodeToString()
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return@runCatching null
        when (arr.size) {
            // [salt, name, value] — property disclosure
            3 -> {
                val name = (arr[1] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
                DisclosureEntry.ObjectProperty(name, arr[2])
            }
            // [salt, value] — array-element disclosure
            2 -> DisclosureEntry.ArrayElement(arr[1])
            else -> null
        }
    }.getOrNull()

    /**
     * The digest is base64url-no-pad of the hash of the **disclosure token bytes as they
     * appear in the SD-JWT** (i.e., the base64url-encoded string, not its decoded JSON).
     * See SD-JWT §4.2.
     */
    private fun computeDigest(token: String, digestAlg: String): String {
        val hash = MessageDigest.getInstance(digestAlg).digest(token.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    private fun digestAlgFromName(name: String): String? = when (name.lowercase()) {
        "sha-256" -> "SHA-256"
        "sha-384" -> "SHA-384"
        "sha-512" -> "SHA-512"
        else -> null
    }

    private fun resolveElement(
        element: JsonElement,
        disclosures: Map<String, DisclosureEntry>,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val out = LinkedHashMap<String, JsonElement>(element.size)
            for ((key, value) in element) {
                if (key == SD_KEY) continue // handled separately below
                out[key] = resolveElement(value, disclosures)
            }
            (element[SD_KEY] as? JsonArray)?.forEach { digestEntry ->
                val digest = (digestEntry as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val disclosure = disclosures[digest] ?: return@forEach
                // SD-JWT §4.2.4.1: `_sd` digests resolve to *object property* disclosures.
                // Array-element disclosures appear inline as `{"...": digest}` placeholders.
                if (disclosure is DisclosureEntry.ObjectProperty) {
                    out[disclosure.name] = resolveElement(disclosure.value, disclosures)
                }
            }
            JsonObject(out)
        }
        is JsonArray -> {
            val out = ArrayList<JsonElement>(element.size)
            for (item in element) {
                val placeholder = item as? JsonObject
                if (placeholder != null &&
                    placeholder.size == 1 &&
                    placeholder.containsKey(ARRAY_DISCLOSURE_KEY)
                ) {
                    val digest = (placeholder[ARRAY_DISCLOSURE_KEY] as? JsonPrimitive)
                        ?.contentOrNull
                    val disclosure = digest?.let(disclosures::get)
                    if (disclosure is DisclosureEntry.ArrayElement) {
                        out += resolveElement(disclosure.value, disclosures)
                    }
                    // Else: array element was selectively undisclosed — skip.
                } else {
                    out += resolveElement(item, disclosures)
                }
            }
            JsonArray(out)
        }
        else -> element
    }
}
