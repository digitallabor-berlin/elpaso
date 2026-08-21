package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadata
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadataPayloadDto
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.toDomain
import dev.digitallabor.elpaso.wallet.mdoc.MdocX5cExtractor
import dev.digitallabor.elpaso.wallet.vct.SdJwtHeaderReader
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import javax.security.auth.x500.X500Principal

/**
 * Verifies a signed credential-metadata JWT per paso-proof-metadata.md §6, and
 * decodes the payload to [CredentialMetadata] on success.
 *
 * Every step from the spec is enforced (typ header, signature, x5c chain trust,
 * iss/exp/sub claims, credential cross-binding). Failure modes are returned as
 * `Result.failure` so callers can log and fall back to hardcoded renderers
 * without throwing.
 */
class CredentialMetadataVerifier(
    private val trustListService: TrustListService,
) {

    fun verify(
        jwt: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<CredentialMetadata> = runCatching {
        val signed = SignedJWT.parse(jwt)

        // §6.1 — typ MUST be credential-metadata+jwt
        val typ = signed.header.type?.type
        check(typ == EXPECTED_TYP) { "metadata JWT has typ=$typ; expected $EXPECTED_TYP" }

        // §6.2/§6.3 — extract & validate x5c chain
        val x5cChain = signed.header.x509CertChain
            ?.map {
                val der = it.decode()
                java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(der.inputStream()) as X509Certificate
            }
            ?: error("metadata JWT missing x5c header")
        check(x5cChain.isNotEmpty()) { "metadata JWT x5c chain is empty" }
        validateChain(x5cChain, now)

        // §6.2 — signature
        verifySignature(signed, x5cChain.first())

        // Decode payload before remaining checks so we can compare iss/sub
        val payloadDto = HttpClientFactory.json.decodeFromString(
            CredentialMetadataPayloadDto.serializer(),
            signed.payload.toString(),
        )

        // §6.3 — trust store check (issuer pinned by ID, optionally by leaf fingerprint)
        check(trustListService.isIssuerTrusted(payloadDto.iss, x5cChain)) {
            "metadata JWT issuer ${payloadDto.iss} not trusted (or leaf fingerprint mismatch)"
        }

        // §6.4 — iss matches Credential Issuer Identifier
        check(payloadDto.iss == credential.issuerId) {
            "metadata JWT iss=${payloadDto.iss} ≠ credential issuerId=${credential.issuerId}"
        }

        // §6.5 — exp not passed
        val expInstant = Instant.ofEpochSecond(payloadDto.exp)
        check(now.isBefore(expInstant)) {
            "metadata JWT expired at $expInstant (now=$now)"
        }

        // §6.6 — sub matches credential type identifier (vct for SD-JWT, doctype for mdoc)
        check(payloadDto.sub == credential.configurationId) {
            "metadata JWT sub=${payloadDto.sub} ≠ credential ${credential.format} type=${credential.configurationId}"
        }

        // §6.6 — cross-binding: root CA identity + leaf subject identity
        val credentialChain = extractCredentialX5c(credential)
            ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
        crossBind(metadataChain = x5cChain, credentialChain = credentialChain)

        payloadDto.toDomain()
    }

    private fun validateChain(chain: List<X509Certificate>, now: Instant) {
        chain.forEach { cert ->
            cert.checkValidity(java.util.Date.from(now))
        }
        // Walk the chain — each non-leaf SHOULD sign the cert preceding it.
        // Self-signed roots terminate the chain.
        for (i in 0 until chain.size - 1) {
            val signed = chain[i]
            val issuer = chain[i + 1]
            try {
                signed.verify(issuer.publicKey)
            } catch (e: Exception) {
                throw IllegalStateException("metadata JWT x5c link ${i}→${i + 1} fails verification", e)
            }
        }
    }

    private fun verifySignature(signed: SignedJWT, leaf: X509Certificate) {
        val publicKey = leaf.publicKey
        val verifier: JWSVerifier = when (signed.header.algorithm) {
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512 -> {
                val ec = publicKey as? ECPublicKey
                    ?: throw IllegalStateException("metadata JWT alg=${signed.header.algorithm} requires EC key in leaf cert")
                ECDSAVerifier(ec)
            }
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512 -> {
                val rsa = publicKey as? RSAPublicKey
                    ?: throw IllegalStateException("metadata JWT alg=${signed.header.algorithm} requires RSA key in leaf cert")
                RSASSAVerifier(rsa)
            }
            else -> throw IllegalStateException("metadata JWT unsupported alg=${signed.header.algorithm}")
        }
        try {
            check(signed.verify(verifier)) { "metadata JWT signature verification returned false" }
        } catch (e: JOSEException) {
            throw IllegalStateException("metadata JWT signature verification failed", e)
        }
    }

    private fun extractCredentialX5c(credential: Credential): List<X509Certificate>? = when (credential.format) {
        Format.SdJwtVc -> SdJwtHeaderReader.extractX5c(credential.payload)
        Format.MsoMdoc -> MdocX5cExtractor.extractX5c(credential.payload)
    }

    private fun crossBind(
        metadataChain: List<X509Certificate>,
        credentialChain: List<X509Certificate>,
    ) {
        check(credentialChain.isNotEmpty()) { "credential x5c chain is empty" }
        val metadataRoot = metadataChain.last()
        val credentialRoot = credentialChain.last()
        check(metadataRoot.encoded.contentEquals(credentialRoot.encoded)) {
            "metadata root CA does not match credential root CA"
        }
        val metadataLeafSubject: X500Principal = metadataChain.first().subjectX500Principal
        val credentialLeafSubject: X500Principal = credentialChain.first().subjectX500Principal
        check(metadataLeafSubject == credentialLeafSubject) {
            "metadata leaf subject $metadataLeafSubject ≠ credential leaf subject $credentialLeafSubject"
        }
    }

    companion object {
        private const val EXPECTED_TYP = "credential-metadata+jwt"
    }
}
