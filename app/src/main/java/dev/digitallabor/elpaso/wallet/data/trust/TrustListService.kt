package dev.digitallabor.elpaso.wallet.data.trust

import android.content.Context
import com.nimbusds.jose.jwk.JWK
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * One issuer's trust policy.
 *
 * [signature_mechanism] has **no default on purpose**: a missing declaration fails the
 * asset parse at startup rather than silently picking one, because it is an unanswered
 * policy question, not a default (spec §5.1). [jwk_thumbprints] is the key-set analogue
 * of [x5c_sha256_fingerprints] — empty means "trust the whole published set", the same
 * trust-by-identifier stance the empty fingerprint list already takes.
 */
@Serializable
data class TrustedIssuer(
    val id: String,
    val label: String,
    val signature_mechanism: SignatureMechanism,
    val x5c_sha256_fingerprints: List<String> = emptyList(),
    val jwk_thumbprints: List<String> = emptyList(),
)

@Serializable
data class TrustedVerifier(
    val id: String,
    val label: String,
    val x509_sans: List<String> = emptyList(),
    val x5c_sha256_fingerprints: List<String> = emptyList(),
)

@Serializable
internal data class IssuersFile(
    val issuers: List<TrustedIssuer>,
)

@Serializable
private data class VerifiersFile(
    val verifiers: List<TrustedVerifier>,
)

class TrustListService(
    context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val issuers: List<TrustedIssuer>
    private val verifiers: List<TrustedVerifier>

    init {
        issuers =
            context.assets.open("trusted_issuers.json").use {
                parseIssuers(it.bufferedReader().readText())
            }
        verifiers =
            context.assets
                .open("trusted_verifiers.json")
                .use { read(it, VerifiersFile.serializer()) }
                .verifiers
    }

    fun listIssuers(): List<TrustedIssuer> = issuers

    fun listVerifiers(): List<TrustedVerifier> = verifiers

    fun issuerLabel(issuerId: String): String? = issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) }?.label

    fun isIssuerTrusted(
        issuerId: String,
        x5cChain: List<X509Certificate>? = null,
    ): Boolean {
        val entry = issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) } ?: return false
        if (entry.x5c_sha256_fingerprints.isEmpty()) return true
        if (x5cChain.isNullOrEmpty()) return false
        val leafFingerprint = sha256Hex(x5cChain.first().encoded)
        return entry.x5c_sha256_fingerprints.any { it.equals(leafFingerprint, ignoreCase = true) }
    }

    /**
     * The one Issuer Signature Mechanism permitted for [issuerId], or null when the
     * issuer is absent from the trust list. Null is a rejection, not a licence to guess.
     */
    fun mechanismFor(issuerId: String): SignatureMechanism? =
        issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) }?.signature_mechanism

    /**
     * Whether [jwk] is a key the wallet accepts for [issuerId], by RFC 7638 SHA-256
     * thumbprint. The key-set counterpart of [isIssuerTrusted]'s leaf-fingerprint check;
     * an empty pin list trusts the whole published set.
     */
    fun isKeyTrusted(
        issuerId: String,
        jwk: JWK,
    ): Boolean {
        val entry = issuers.firstOrNull { it.id.equals(issuerId, ignoreCase = true) } ?: return false
        return keyTrusted(entry, jwk.computeThumbprint().toString())
    }

    fun resolveVerifier(
        clientId: String,
        x5cChain: List<X509Certificate>?,
    ): TrustedVerifier? {
        val byId = verifiers.firstOrNull { it.id.equals(clientId, ignoreCase = true) }
        if (byId != null) return byId
        if (x5cChain.isNullOrEmpty()) return null
        val sans = extractDnsSans(x5cChain.first())
        val bySan =
            verifiers.firstOrNull { v ->
                v.x509_sans.any { san -> sans.any { it.equals(san, ignoreCase = true) } }
            } ?: return null
        if (bySan.x5c_sha256_fingerprints.isEmpty()) return bySan
        val leafFingerprint = sha256Hex(x5cChain.first().encoded)
        return if (bySan.x5c_sha256_fingerprints.any { it.equals(leafFingerprint, ignoreCase = true) }) bySan else null
    }

    /**
     * Decodes base64 `x5c` entries. Uses `java.util.Base64` deliberately — the Android
     * codec returns null under the JVM unit-test stubs, and nothing here needs it.
     */
    fun parseX5c(x5cBase64: List<String>): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val decoder = java.util.Base64.getMimeDecoder()
        return x5cBase64.map { b64 ->
            cf.generateCertificate(decoder.decode(b64).inputStream()) as X509Certificate
        }
    }

    private fun extractDnsSans(cert: X509Certificate): List<String> =
        cert.subjectAlternativeNames
            ?.mapNotNull { entry ->
                if (entry.size >= 2 && entry[0] == 2) entry[1] as? String else null
            }.orEmpty()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> read(
        input: InputStream,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T = json.decodeFromString(deserializer, input.bufferedReader().readText())

    companion object {
        private val POLICY_JSON = Json { ignoreUnknownKeys = true }

        /**
         * Pure parse of `trusted_issuers.json`, split out so the policy is unit-testable
         * without a `Context` or an asset. Throws [kotlinx.serialization.SerializationException]
         * on a missing or unknown `signature_mechanism` — which is the intended behaviour.
         */
        internal fun parseIssuers(jsonText: String): List<TrustedIssuer> =
            POLICY_JSON.decodeFromString(IssuersFile.serializer(), jsonText).issuers

        /**
         * Pure thumbprint match. Comparison is case-sensitive because base64url is;
         * an empty pin list trusts the whole set.
         */
        internal fun keyTrusted(
            entry: TrustedIssuer,
            thumbprint: String,
        ): Boolean {
            if (entry.jwk_thumbprints.isEmpty()) return true
            return entry.jwk_thumbprints.any { it == thumbprint }
        }
    }
}
