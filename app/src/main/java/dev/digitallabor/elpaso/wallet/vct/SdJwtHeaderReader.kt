package dev.digitallabor.elpaso.wallet.vct

import dev.digitallabor.elpaso.wallet.util.B64u
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * JOSE-header inspection helpers for SD-JWT-VC credential payloads. Pure decoding, no
 * signature verification.
 *
 * **Nothing else verifies it either.** The wallet never checks a credential's
 * issuer-signed JWT — not at issuance (`eudi-openid4vci-kt` hands the credential over as
 * an opaque string; see `IssuanceClient.encodeIssued`) and not at presentation
 * (`SdJwtPresentationBuilder` signs a KB-JWT over the payload without validating it). The
 * only issuer signatures this app verifies belong to the two PaSO metadata JWTs, via
 * `data/trust/IssuerSignedJwt.kt`; [extractX5c] exists to cross-bind those against the
 * credential's chain, which is a different and weaker claim than verifying the credential.
 *
 * Closing that gap — and the `kid`/key-set issuer signature mechanism that depends on it —
 * is designed in
 * `docs/superpowers/specs/2026-08-31-credential-signature-verification-design.md`.
 */
object SdJwtHeaderReader {
    /** Returns the issuer-signed JWT segment (everything before the first `~`). */
    fun issuerJwt(payload: ByteArray): String? =
        runCatching {
            val sdJwt = payload.decodeToString()
            sdJwt.substringBefore('~').takeIf { it.isNotBlank() }
        }.getOrNull()

    /**
     * Decodes the `x5c` JOSE header to a list of [X509Certificate]s (leaf first).
     * Returns null if the credential has no `x5c` header or the header is malformed.
     */
    fun extractX5c(payload: ByteArray): List<X509Certificate>? =
        runCatching {
            val jwt = issuerJwt(payload) ?: return@runCatching null
            val parts = jwt.split('.')
            if (parts.size < 2) return@runCatching null
            val headerJson = B64u.decode(parts[0]).decodeToString()
            val x5c = Json.parseToJsonElement(headerJson).jsonObject["x5c"]?.jsonArray ?: return@runCatching null
            val cf = CertificateFactory.getInstance("X.509")
            x5c.map { entry ->
                val b64 = entry.jsonPrimitive.content
                val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                cf.generateCertificate(der.inputStream()) as X509Certificate
            }
        }.getOrNull()
}
