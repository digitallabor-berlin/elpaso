package dev.digitallabor.elpaso.wallet.issuance

import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.trust.IssuerSignedJwt
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadata
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadataPayloadDto
import dev.digitallabor.elpaso.wallet.domain.model.toDomain
import java.time.Instant

/**
 * Verifies a signed credential-metadata JWT per paso-proof-metadata.md §6, and
 * decodes the payload to [CredentialMetadata] on success.
 *
 * Every step from the spec is enforced (typ header, signature, x5c chain trust,
 * iss/exp/sub claims, credential cross-binding). Failure modes are returned as
 * `Result.failure` so callers can log and fall back to hardcoded renderers
 * without throwing.
 *
 * The x5c mechanics are shared with [AdhocTransactionMetadataVerifier] via
 * [IssuerSignedJwt]; what stays here is the ordering of this channel's spec steps
 * and the claims unique to it (`credential_metadata_uri`, `credential_metadata`).
 */
class CredentialMetadataVerifier(
    private val trustListService: TrustListService,
) {
    fun verify(
        jwt: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<CredentialMetadata> =
        runCatching {
            val signed = SignedJWT.parse(jwt)

            // §6.1 — typ MUST be credential-metadata+jwt
            val typ = signed.header.type?.type
            check(typ == EXPECTED_TYP) { "metadata JWT has typ=$typ; expected $EXPECTED_TYP" }

            // §6.2/§6.3 — extract & validate x5c chain
            val x5cChain = IssuerSignedJwt.readX5cChain(signed, LABEL)
            IssuerSignedJwt.validateChain(x5cChain, now, LABEL)

            // §6.2 — signature
            IssuerSignedJwt.verifySignature(signed, x5cChain.first(), LABEL)

            // Decode payload before remaining checks so we can compare iss/sub
            val payloadDto =
                HttpClientFactory.json.decodeFromString(
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
            val credentialChain =
                IssuerSignedJwt.credentialChain(credential)
                    ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
            IssuerSignedJwt.crossBind(jwtChain = x5cChain, credentialChain = credentialChain, label = LABEL)

            payloadDto.toDomain()
        }

    companion object {
        private const val EXPECTED_TYP = "credential-metadata+jwt"
        private const val LABEL = "metadata JWT"
    }
}
