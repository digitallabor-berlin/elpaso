package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.mdoc.MdocX5cExtractor
import dev.digitallabor.elpaso.wallet.vct.SdJwtHeaderReader
import java.security.PublicKey
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

    /**
     * Verifies the JWS signature against [key].
     *
     * Generalised from the certificate-only form so the JWT VC Issuer Metadata mechanism
     * can reuse it with a JWK-derived key. The certificate overload below delegates here,
     * so the x5c path is byte-for-byte the same verification it always was — the two
     * mechanisms differ in *how the key is obtained*, never in how the signature is
     * checked.
     */
    fun verifySignature(
        signed: SignedJWT,
        key: PublicKey,
        label: String,
    ) {
        val verifier: JWSVerifier =
            when (signed.header.algorithm) {
                JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 -> {
                    val ec =
                        key as? ECPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires an EC key",
                            )
                    ECDSAVerifier(ec)
                }

                JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
                -> {
                    val rsa =
                        key as? RSAPublicKey
                            ?: throw IllegalStateException(
                                "$label alg=${signed.header.algorithm} requires an RSA key",
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

    /** Verifies the JWS signature against the leaf certificate's public key. */
    fun verifySignature(
        signed: SignedJWT,
        leaf: X509Certificate,
        label: String,
    ) = verifySignature(signed, leaf.publicKey, label)

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

    /**
     * The credential binding of paso-proof-metadata.md §7 step 6, **key-set bullet**: the
     * metadata JWT must have been verified "using a key from the same issuer key set that
     * verifies the credential itself".
     *
     * The certificate bullet's analogue is [crossBind]; the two are mutually exclusive by
     * construction, and which one applies is decided by the credential's *recorded*
     * mechanism, never by the metadata JWT's header. That is
     * draft-ietf-oauth-sd-jwt-vc-11 §10.2 one level up: were the header allowed to decide,
     * a verifier would choose which binding rule the wallet applies to a credential simply
     * by choosing what to put in its own JWT.
     *
     * Three checks, in this order, each closing a distinct hole:
     *
     * 1. The credential was itself verified by a key set. A credential verified by x5c —
     *    or one stored before the wallet verified anything — has no key-set anchor, so
     *    this branch does not apply to it and proceeding would invent one.
     * 2. [keySet] is *the same* key set, identified by [IssuerKeySet.sourceUrl]. "Same
     *    issuer" is not what the spec says and is materially weaker: an issuer may publish
     *    more than one set, and only one of them verified this credential.
     * 3. The signature verifies under a key from that set. `kid` narrows the candidates
     *    when present; §5.2 only RECOMMENDS it, so its absence means trying each key
     *    rather than failing.
     */
    fun bindToKeySet(
        signed: SignedJWT,
        credential: Credential,
        keySet: IssuerKeySet,
        label: String,
    ) {
        // 1 — the credential's own mechanism decides, and it must be the key-set one.
        check(credential.issuerBinding == IssuerBinding.KeySet) {
            "$label: credential ${credential.id} was not verified by a key set " +
                "(binding=${credential.issuerBinding}); the key-set binding rule does not apply"
        }

        // An x5c on the metadata JWT under this branch is a mechanism-confusion attempt,
        // not a redundancy to ignore.
        check(signed.header.x509CertChain == null) {
            "$label mechanism confusion: x5c present on a metadata JWT bound to a key set"
        }

        // 2 — the SAME key set, not merely one of this issuer's.
        val recordedSource = credential.issuerKeySetSource
        check(recordedSource != null && recordedSource == keySet.sourceUrl) {
            "$label was resolved from a different issuer key set: ${keySet.sourceUrl} ≠ $recordedSource"
        }

        // 3 — verify under a key from that set.
        val kid = signed.header.keyID
        val candidates = keySet.byKid(kid)
        check(candidates.isNotEmpty()) {
            "$label kid=$kid names no key in the issuer key set from ${keySet.sourceUrl}"
        }
        val verified =
            candidates.any { candidate ->
                runCatching { verifySignature(signed, publicKeyOf(candidate, label), label) }.isSuccess
            }
        check(verified) {
            "$label verified against no key in the issuer key set from ${keySet.sourceUrl}"
        }
    }

    /** A JWK's public key, for [verifySignature]. Throws for a symmetric JWK. */
    private fun publicKeyOf(
        jwk: JWK,
        label: String,
    ): PublicKey =
        (jwk as? AsymmetricJWK)?.toPublicKey()
            ?: throw IllegalStateException("$label key ${jwk.keyID} is not an asymmetric JWK")
}
