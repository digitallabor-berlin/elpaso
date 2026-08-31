package dev.digitallabor.elpaso.wallet.vct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * JOSE-header inspection helpers for SD-JWT-VC credential payloads. Pure decoding, no
 * signature verification — verification lives in
 * `data/trust/CredentialSignatureVerifier.kt`, which calls [issuerJwt] to get the
 * segment it then parses and checks.
 *
 * Decoding deliberately uses `java.util.Base64` rather than `android.util.Base64`.
 * The Android codec returns null under the JVM unit-test stubs
 * (`testOptions.unitTests.isReturnDefaultValues = true`), which made every x5c path
 * that reaches this object untestable. `java.util.Base64` exists from API 26 and
 * minSdk is 29, so nothing is lost on device.
 */
object SdJwtHeaderReader {
    /** base64url, tolerant of absent padding as JOSE requires. */
    private val headerDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Standard base64, tolerant of line breaks, as `x5c` entries may carry. */
    private val derDecoder: Base64.Decoder = Base64.getMimeDecoder()

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
            val headerJson = headerDecoder.decode(parts[0]).decodeToString()
            val x5c = Json.parseToJsonElement(headerJson).jsonObject["x5c"]?.jsonArray ?: return@runCatching null
            val cf = CertificateFactory.getInstance("X.509")
            x5c.map { entry ->
                val der = derDecoder.decode(entry.jsonPrimitive.content)
                cf.generateCertificate(der.inputStream()) as X509Certificate
            }
        }.getOrNull()
}
