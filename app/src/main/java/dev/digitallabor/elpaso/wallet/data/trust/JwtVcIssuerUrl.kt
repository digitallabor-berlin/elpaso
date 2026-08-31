package dev.digitallabor.elpaso.wallet.data.trust

import java.net.URI

/**
 * Builds the JWT VC Issuer Metadata configuration URL from an `iss` value, per
 * draft-ietf-oauth-sd-jwt-vc-11 §5 and §5.1.
 *
 * The well-known segment is inserted **between the host component and the path
 * component**, not appended:
 *
 * ```
 * https://example.com             → https://example.com/.well-known/jwt-vc-issuer
 * https://example.com/tenant/1234 → https://example.com/.well-known/jwt-vc-issuer/tenant/1234
 * ```
 *
 * §5.1 also removes any terminating `/` from the path before inserting. Getting this
 * wrong produces a 404 against a conformant issuer, which is easy to misread as "the
 * issuer does not support the key-set mechanism", so it is a pure function with its own
 * tests rather than a string concatenation at a call site.
 *
 * `iss` MUST be an HTTPS URL with no query and no fragment (§5); anything else is a
 * failure, never a repaired value.
 */
object JwtVcIssuerUrl {
    const val WELL_KNOWN_PATH = "/.well-known/jwt-vc-issuer"

    fun of(iss: String): Result<String> =
        runCatching {
            val uri = URI(iss)
            check(uri.scheme?.lowercase() == "https") { "iss must use the https scheme, got scheme=${uri.scheme}" }
            val host = uri.host
            check(!host.isNullOrBlank()) { "iss has no host component: $iss" }
            check(uri.rawQuery == null) { "iss must not carry a query component: $iss" }
            check(uri.rawFragment == null) { "iss must not carry a fragment component: $iss" }
            check(uri.rawUserInfo == null) { "iss must not carry userinfo: $iss" }

            val authority = if (uri.port == -1) host else "$host:${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            "https://$authority$WELL_KNOWN_PATH$path"
        }
}
