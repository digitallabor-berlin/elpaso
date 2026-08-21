package dev.digitallabor.elpaso.wallet.mdoc

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MajorType
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Extracts the issuer x5chain from an mso_mdoc credential payload.
 *
 * The wallet stores EUDI-issued mso_mdoc credentials as a base64url-encoded
 * `IssuerSigned` CBOR map. Inside it, `issuerAuth` is a COSE_Sign1 structure whose
 * unprotected header carries the issuer certificate chain at label 33 (per
 * RFC 9360 / ISO 18013-5). The chain may be a single DER bstr or an array of
 * DER bstrs.
 */
object MdocX5cExtractor {

    private const val COSE_HEADER_X5CHAIN = 33L
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun extractX5c(credentialPayload: ByteArray): List<X509Certificate>? = runCatching {
        val decoded = runCatching { urlDecoder.decode(credentialPayload.decodeToString()) }
            .getOrElse { return@runCatching null }
        val issuerSigned = Cbor.decode(decoded)
        val issuerSignedMap = issuerSigned.asMap
        val issuerAuth = issuerSignedMap.entries
            .firstOrNull { it.key.asTstr == "issuerAuth" }?.value
            ?: return@runCatching null
        // COSE_Sign1 = [protected bstr, unprotected map, payload, signature]
        val sign1 = issuerAuth.asArray
        if (sign1.size < 2) return@runCatching null
        val protectedBytes = sign1[0].asBstr
        val unprotectedMap = sign1[1].asMap

        val chainItem = findX5Chain(unprotectedMap)
            ?: findX5Chain(decodeProtectedHeader(protectedBytes))
            ?: return@runCatching null

        val cf = CertificateFactory.getInstance("X.509")
        val certBytesList = when (chainItem.majorType) {
            MajorType.BYTE_STRING -> listOf(chainItem.asBstr)
            MajorType.ARRAY -> chainItem.asArray.map { it.asBstr }
            else -> return@runCatching null
        }
        certBytesList.map { der ->
            cf.generateCertificate(der.inputStream()) as X509Certificate
        }
    }.getOrNull()

    private fun findX5Chain(headerMap: Map<DataItem, DataItem>?): DataItem? {
        if (headerMap == null) return null
        return headerMap.entries.firstOrNull { (k, _) ->
            k.majorType == MajorType.UNSIGNED_INTEGER && k.asNumber == COSE_HEADER_X5CHAIN
        }?.value
    }

    private fun decodeProtectedHeader(protectedBytes: ByteArray): Map<DataItem, DataItem>? = runCatching {
        if (protectedBytes.isEmpty()) return@runCatching null
        Cbor.decode(protectedBytes).asMap
    }.getOrNull()
}
