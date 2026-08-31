package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.mdoc.MdocX5cExtractor
import dev.digitallabor.elpaso.wallet.vct.SdJwtHeaderReader
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The x5c-based checks shared by the two issuer-signed JWTs PaSO defines:
 * the `credential-metadata+jwt` fetched from `credential_metadata_uri`
 * (paso-proof-metadata.md §7) and the `adhoc-transaction-metadata+jwt`
 * delivered inside a `transaction_data` entry (§5.3).
 *
 * §5.3 steps 2, 3 and 6 are defined by reference to §7 steps 2, 3 and 6 — they are
 * the same checks, not merely similar ones. Keeping one implementation means a
 * correction to the trust logic cannot land on one channel and miss the other; the
 * ad-hoc channel is the one delivered over an untrusted transport (§5.5), so a
 * divergence there would be the more dangerous of the two.
 *
 * Each function takes a `label` used only in failure messages, so a log line still
 * says which of the two JWTs failed.
 *
 * Every check throws [IllegalStateException] on failure. Callers wrap the sequence
 * in `runCatching` and surface `Result.failure`; nothing here returns a boolean,
 * because a silently-ignored `false` is exactly the mistake this centralisation is
 * meant to make impossible.
 */
internal object IssuerSignedJwt {
    /** Reads and decodes the `x5c` JOSE header. Absent or empty is a hard failure. */
    fun readX5cChain(
        signed: SignedJWT,
        label: String,
    ): List<X509Certificate> {
        val chain =
            signed.header.x509CertChain
                ?.map {
                    CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(it.decode().inputStream()) as X509Certificate
                }
                ?: error("$label missing x5c header")
        check(chain.isNotEmpty()) { "$label x5c chain is empty" }
        return chain
    }

    /**
     * Validity window of every certificate, then the signing links between them.
     * A self-signed root terminates the chain, so the last element signs nothing.
     */
    fun validateChain(
        chain: List<X509Certificate>,
        now: Instant,
        label: String,
    ) {
        chain.forEach { cert -> cert.checkValidity(Date.from(now)) }
        for (i in 0 until chain.size - 1) {
            val subject = chain[i]
            val issuer = chain[i + 1]
            try {
                subject.verify(issuer.publicKey)
            } catch (e: Exception) {
                throw IllegalStateException("$label x5c link $i→${i + 1} fails verification", e)
            }
        }
    }

    /** Verifies the JWS signature against the leaf certificate's public key. */
    fun verifySignature(
        signed: SignedJWT,
        leaf: X509Certificate,
        label: String,
    ) {
        val publicKey = leaf.publicKey
        val verifier: JWSVerifier =
            when (signed.header.algorithm) {
                JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 -> {
                    val ec =
                        publicKey as? ECPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires EC key in leaf cert",
                            )
                    ECDSAVerifier(ec)
                }

                JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
                -> {
                    val rsa =
                        publicKey as? RSAPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires RSA key in leaf cert",
                            )
                    RSASSAVerifier(rsa)
                }

                else -> {
                    throw IllegalStateException("$label unsupported alg=${signed.header.algorithm}")
                }
            }
        try {
            check(signed.verify(verifier)) { "$label signature verification returned false" }
        } catch (e: JOSEException) {
            throw IllegalStateException("$label signature verification failed", e)
        }
    }

    /** The credential's own certificate chain, read from its format-specific header. */
    fun credentialChain(credential: Credential): List<X509Certificate>? =
        when (credential.format) {
            Format.SdJwtVc -> SdJwtHeaderReader.extractX5c(credential.payload)
            Format.MsoMdoc -> MdocX5cExtractor.extractX5c(credential.payload)
        }

    /**
     * The credential binding of §7 step 6, second bullet: same root CA, same leaf
     * Subject. This — not the signature — is what pins the JWT to *this* credential's
     * issuer (§5.5). The binding deliberately does not demand the same key, so an
     * Attestation Provider may sign metadata with a dedicated key.
     */
    fun crossBind(
        jwtChain: List<X509Certificate>,
        credentialChain: List<X509Certificate>,
        label: String,
    ) {
        check(credentialChain.isNotEmpty()) { "credential x5c chain is empty" }
        val jwtRoot = jwtChain.last()
        val credentialRoot = credentialChain.last()
        check(jwtRoot.encoded.contentEquals(credentialRoot.encoded)) {
            "$label root CA does not match credential root CA"
        }
        val jwtLeafSubject: X500Principal = jwtChain.first().subjectX500Principal
        val credentialLeafSubject: X500Principal = credentialChain.first().subjectX500Principal
        check(jwtLeafSubject == credentialLeafSubject) {
            "$label leaf subject $jwtLeafSubject ≠ credential leaf subject $credentialLeafSubject"
        }
    }
}
