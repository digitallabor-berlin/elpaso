package dev.digitallabor.elpaso.wallet.issuance

import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.IssuerSignedJwt
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadata
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadataPayloadDto
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
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
    private val keySetResolver: IssuerKeySetResolver,
) {
    suspend fun verify(
        jwt: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<CredentialMetadata> =
        runCatching {
            val signed = SignedJWT.parse(jwt)

            // §6.1 — typ MUST be credential-metadata+jwt
            val typ = signed.header.type?.type
            check(typ == EXPECTED_TYP) { "metadata JWT has typ=$typ; expected $EXPECTED_TYP" }

            // §6.2/§6.3 — how the signing key is established depends on how THIS
            // credential was verified at issuance, not on what this JWT's header claims.
            // See `IssuerBinding` and sd-jwt-vc §10.2.
            val binding =
                credential.issuerBinding
                    ?: error(
                        "credential ${credential.id} has no recorded issuer binding; " +
                            "it predates credential verification and metadata cannot be bound to it",
                    )

            val x5cChain =
                when (binding) {
                    IssuerBinding.X5c -> {
                        val chain = IssuerSignedJwt.readX5cChain(signed, LABEL)
                        IssuerSignedJwt.validateChain(chain, now, LABEL)
                        IssuerSignedJwt.verifySignature(signed, chain.first(), LABEL)
                        chain
                    }

                    // Signature verification is deferred to the binding step below: under
                    // the key-set mechanism the key IS the binding, so splitting them would
                    // mean resolving the key set twice.
                    IssuerBinding.KeySet -> {
                        null
                    }
                }

            // Decode payload before remaining checks so we can compare iss/sub
            val payloadDto =
                HttpClientFactory.json.decodeFromString(
                    CredentialMetadataPayloadDto.serializer(),
                    signed.payload.toString(),
                )

            // §6.3 — trust store check. Under the key-set branch `x5cChain` is null and the
            // leaf-fingerprint pin does not apply; the key-level pin is `isKeyTrusted`,
            // enforced against the credential at issuance by `CredentialSignatureVerifier`.
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

            // §6.6 — bind the JWT to THIS credential's issuer. Which rule applies is fixed
            // by `binding` above; the two are mutually exclusive and there is no fallback.
            when (binding) {
                IssuerBinding.X5c -> {
                    val credentialChain =
                        IssuerSignedJwt.credentialChain(credential)
                            ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
                    IssuerSignedJwt.crossBind(
                        jwtChain = requireNotNull(x5cChain),
                        credentialChain = credentialChain,
                        label = LABEL,
                    )
                }

                IssuerBinding.KeySet -> {
                    val keySet = keySetResolver.resolve(credential.issuerId, now).getOrThrow()
                    IssuerSignedJwt.bindToKeySet(
                        signed = signed,
                        credential = credential,
                        keySet = keySet,
                        label = LABEL,
                    )
                }
            }

            payloadDto.toDomain()
        }

    companion object {
        private const val EXPECTED_TYP = "credential-metadata+jwt"
        private const val LABEL = "metadata JWT"
    }
}
