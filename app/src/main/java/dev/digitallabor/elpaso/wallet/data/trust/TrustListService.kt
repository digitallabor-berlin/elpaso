package dev.digitallabor.elpaso.wallet.data.trust

import android.content.Context
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrustedIssuer(
    val id: String,
    val label: String,
    val x5c_sha256_fingerprints: List<String> = emptyList(),
)

@Serializable
data class TrustedVerifier(
    val id: String,
    val label: String,
    val x509_sans: List<String> = emptyList(),
    val x5c_sha256_fingerprints: List<String> = emptyList(),
)

@Serializable
private data class IssuersFile(val issuers: List<TrustedIssuer>)

@Serializable
private data class VerifiersFile(val verifiers: List<TrustedVerifier>)

class TrustListService(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val issuers: List<TrustedIssuer>
    private val verifiers: List<TrustedVerifier>

    init {
        issuers = context.assets.open("trusted_issuers.json").use { read(it, IssuersFile.serializer()) }.issuers
        verifiers = context.assets.open("trusted_verifiers.json").use { read(it, VerifiersFile.serializer()) }.verifiers
    }

    fun listIssuers(): List<TrustedIssuer> = issuers
    fun listVerifiers(): List<TrustedVerifier> = verifiers

    fun issuerLabel(issuerId: String): String? =
        issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) }?.label

    fun isIssuerTrusted(issuerId: String, x5cChain: List<X509Certificate>? = null): Boolean {
        val entry = issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) } ?: return false
        if (entry.x5c_sha256_fingerprints.isEmpty()) return true
        if (x5cChain.isNullOrEmpty()) return false
        val leafFingerprint = sha256Hex(x5cChain.first().encoded)
        return entry.x5c_sha256_fingerprints.any { it.equals(leafFingerprint, ignoreCase = true) }
    }

    fun resolveVerifier(clientId: String, x5cChain: List<X509Certificate>?): TrustedVerifier? {
        val byId = verifiers.firstOrNull { it.id.equals(clientId, ignoreCase = true) }
        if (byId != null) return byId
        if (x5cChain.isNullOrEmpty()) return null
        val sans = extractDnsSans(x5cChain.first())
        val bySan = verifiers.firstOrNull { v ->
            v.x509_sans.any { san -> sans.any { it.equals(san, ignoreCase = true) } }
        } ?: return null
        if (bySan.x5c_sha256_fingerprints.isEmpty()) return bySan
        val leafFingerprint = sha256Hex(x5cChain.first().encoded)
        return if (bySan.x5c_sha256_fingerprints.any { it.equals(leafFingerprint, ignoreCase = true) }) bySan else null
    }

    fun parseX5c(x5cBase64: List<String>): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return x5cBase64.map { b64 ->
            val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            cf.generateCertificate(der.inputStream()) as X509Certificate
        }
    }

    private fun extractDnsSans(cert: X509Certificate): List<String> =
        cert.subjectAlternativeNames
            ?.mapNotNull { entry ->
                if (entry.size >= 2 && entry[0] == 2) entry[1] as? String else null
            }
            .orEmpty()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> read(input: InputStream, deserializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(deserializer, input.bufferedReader().readText())
}
