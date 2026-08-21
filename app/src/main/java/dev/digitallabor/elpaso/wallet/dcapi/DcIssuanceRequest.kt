package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Maps a W3C Digital Credentials API `create` request into a credential-offer URI the
 * existing [dev.digitallabor.elpaso.wallet.issuance.IssuanceClient] already understands.
 *
 * Deliberately pure Kotlin — no `android.net.`, no `android.util.`, no `org.json.`. All
 * of those live in `android.jar` and stub to null/0/false under JVM unit tests
 * (`isReturnDefaultValues = true`), which would make this logic untestable.
 *
 * The DC API delivers issuer metadata inline; we drop it and let the EUDI library resolve
 * metadata over the network, so the whole existing issuance pipeline applies unchanged. The
 * inline copy is used only to cross-check the issuer identifier before any network call.
 */
object DcIssuanceRequest {
    /** Fixed acknowledgement returned to the caller once the credential is stored. */
    const val ACK_RESPONSE_JSON = """{"protocol":"openid4vci","data":{}}"""

    sealed interface Result {
        data class Offer(
            val offerUri: String,
            val issuerId: String,
        ) : Result

        data class Rejected(
            val reason: String,
        ) : Result
    }

    fun map(requestJson: String): Result {
        val root =
            runCatching { Json.parseToJsonElement(requestJson).jsonObject }.getOrNull()
                ?: return Result.Rejected("Request is not a JSON object")

        val requests =
            runCatching { root["requests"]?.jsonArray }.getOrNull()
                ?: return Result.Rejected("Request has no 'requests' array")

        val entry =
            requests.firstNotNullOfOrNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull()
                val protocol = runCatching { obj?.get("protocol")?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (protocol != null && protocol in SUPPORTED_PROTOCOLS) obj else null
            } ?: return Result.Rejected("No request entry with a supported openid4vci protocol")

        val offer =
            runCatching { entry["data"]?.jsonObject }.getOrNull()
                ?: return Result.Rejected("Request 'data' is not a credential offer object")

        val issuerId =
            runCatching { offer["credential_issuer"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: return Result.Rejected("Offer has no credential_issuer")

        val inlineIssuer =
            runCatching {
                offer["credential_issuer_metadata"]
                    ?.jsonObject
                    ?.get("credential_issuer")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        if (inlineIssuer != null && inlineIssuer != issuerId) {
            return Result.Rejected("Inline metadata issuer does not match credential_issuer")
        }

        val grants = runCatching { offer["grants"]?.jsonObject }.getOrNull()
        if (grants == null || PRE_AUTHORIZED_CODE_GRANT !in grants) {
            return Result.Rejected("Only the pre-authorized_code grant is supported")
        }

        val stripped = JsonObject(offer.filterKeys { it !in DC_API_ONLY_KEYS })
        val encoded = URLEncoder.encode(stripped.toString(), "UTF-8")
        return Result.Offer(
            offerUri = "openid-credential-offer://?credential_offer=$encoded",
            issuerId = issuerId,
        )
    }

    private val SUPPORTED_PROTOCOLS = setOf("openid4vci1.0", "openid4vci-v1", "openid4vci")

    private const val PRE_AUTHORIZED_CODE_GRANT =
        "urn:ietf:params:oauth:grant-type:pre-authorized_code"

    /**
     * Present in a DC API request but not in a spec credential offer. Passing them through
     * risks the library rejecting unknown members.
     */
    private val DC_API_ONLY_KEYS =
        setOf("credential_issuer_metadata", "authorization_server_metadata")
}
