package dev.digitallabor.elpaso.wallet.domain.claims

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.vct.VctClaim
import dev.digitallabor.elpaso.wallet.vct.VctClaimDisplay
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Turns the issuer-supplied claim metadata persisted in
 * [dev.digitallabor.elpaso.wallet.domain.model.Credential.displayMetadataJson] into
 * human-readable labels for requested claim paths.
 *
 * Both label sources land in that one blob — SD-JWT VC Type Metadata fetched from the
 * credential's `vct` URL, and OpenID4VCI issuer metadata captured at offer time — so
 * this resolver is the single lookup for either.
 *
 * Credentials issued before claim metadata was captured simply carry no `claims` array;
 * they resolve to [ClaimLabels.Empty] and callers fall back to the raw path.
 *
 * Pure Kotlin by design: no `android.util.*`, so it is exercisable under the JVM unit
 * tests (which stub the Android framework to null/0/false).
 */
object ClaimLabelResolver {
    fun resolve(
        displayMetadataJson: String,
        locale: Locale = Locale.getDefault(),
    ): ClaimLabels {
        val metadata = parse(displayMetadataJson) ?: return ClaimLabels.Empty
        if (metadata.claims.isEmpty()) return ClaimLabels.Empty

        val byPath = LinkedHashMap<List<String>, String>()
        for (claim in metadata.claims) {
            val key = claim.stringPath()
            // An empty key would collide with every path that reduces to nothing; a path
            // made only of wildcards names no single attribute the consent list can show.
            if (key.isEmpty()) continue
            if (byPath.containsKey(key)) continue
            val label = claim.display.pickLabel(locale) ?: continue
            byPath[key] = label
        }
        return if (byPath.isEmpty()) ClaimLabels.Empty else ClaimLabels(byPath, leafIndex(byPath))
    }

    /**
     * Secondary index from a path's last segment to its label, keeping only segments that
     * exactly one path ends with. A repeated leaf (`address.country` vs
     * `birth_place.country`) is dropped rather than resolved arbitrarily.
     *
     * Exists for one caller: the detail screen renders mso_mdoc claims flattened, because
     * [CredentialClaims] discards the namespace when decoding `IssuerSigned` — so the only
     * key it has is `family_name` while the metadata path is
     * `["org.iso.18013.5.1", "family_name"]`.
     */
    private fun leafIndex(byPath: Map<List<String>, String>): Map<String, String> {
        val counts = byPath.keys.groupingBy { it.last() }.eachCount()
        return byPath.entries
            .filter { counts[it.key.last()] == 1 }
            .associate { it.key.last() to it.value }
    }

    private fun parse(json: String): VctMetadata? {
        if (json.isBlank() || json == "{}") return null
        return runCatching {
            HttpClientFactory.json.decodeFromString(VctMetadata.serializer(), json)
        }.getOrNull()
    }

    /**
     * Reduces a metadata claim path to its string segments, dropping array indices and
     * wildcards. This mirrors `DcqlMatcher.toStringList()`, which keeps only
     * `ClaimPathElement.Claim` when building `Match.requestedClaimPaths` — the two sides
     * must reduce identically or no label would ever match a requested path.
     */
    private fun VctClaim.stringPath(): List<String> =
        path.mapNotNull { element ->
            (element as? JsonPrimitive)
                ?.takeIf { it !is JsonNull && it.isString }
                ?.content
        }

    /**
     * Locale fallback ladder: exact BCP47 tag → language-only match → no-locale default →
     * first entry. Matches `List<LocalizedLabel>.pick` and `CredentialDisplay`'s block
     * picking, so every localized surface in the wallet degrades the same way.
     *
     * Entries with no usable text are dropped before picking, so a blank label in the
     * user's own locale does not shadow a real one in another.
     */
    private fun List<VctClaimDisplay>.pickLabel(locale: Locale): String? {
        val usable = filter { !it.text.isNullOrBlank() }
        if (usable.isEmpty()) return null
        val tag = locale.toLanguageTag()
        val language = locale.language
        val picked =
            usable.firstOrNull { it.localeTag.equals(tag, ignoreCase = true) }
                ?: usable.firstOrNull { it.localeTag?.substringBefore('-').equals(language, ignoreCase = true) }
                ?: usable.firstOrNull { it.localeTag.isNullOrBlank() }
                ?: usable.first()
        return picked.text?.trim()
    }
}

/**
 * Locale-resolved claim labels for one credential, keyed by the claim path reduced to its
 * string segments. Immutable and cheap to hold across recompositions.
 */
class ClaimLabels internal constructor(
    private val byPath: Map<List<String>, String>,
    private val byLeaf: Map<String, String> = emptyMap(),
) {
    /** The issuer's label for [path], or null when the credential's metadata has none. */
    fun labelFor(path: List<String>): String? = byPath[path]

    /**
     * The issuer's label for a claim known only by its last path segment, or null when no
     * path ends with [name] or more than one does.
     *
     * Only for surfaces that have lost the full path — in practice the detail screen's
     * flattened mso_mdoc claims. Deliberately **not** used by the presentation consent
     * list: that list is the record of what the user authorised disclosing, and a
     * last-segment match is a guess. There, an unlabelled path is the safer outcome.
     */
    fun labelForLeaf(name: String): String? = byLeaf[name]

    companion object {
        val Empty = ClaimLabels(emptyMap())
    }
}
