package dev.digitallabor.elpaso.wallet.presentation.txdata

import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.IssuerSignedJwt
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.AdhocMetadataPayloadDto
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.toDomain
import java.time.Instant

/**
 * Verifies the `adhoc-transaction-metadata+jwt` carried inside a `transaction_data`
 * entry, per paso-proof-metadata.md §5.3, and decodes it to the same
 * [TransactionDataTypeMetadata] the stored-metadata channel produces.
 *
 * This JWT arrives from the Relying Party — an untrusted channel (§5.5). Nothing about
 * it may be believed before all seven checks pass, and a failure is **not** a licence
 * to fall back to the stored `credential-metadata+jwt`: §5.3 closes with "The Wallet
 * SHALL NOT fall back to the stored credential metadata entry for a `transaction_data`
 * entry whose `metadata` parameter fails verification". Falling back would let a
 * verifier strip the signature and still get a consent screen rendered, which is why
 * the caller ([TransactionMetadataResolver]) treats failure as incompatibility rather
 * than as absence.
 *
 * The guarantee rests on the credential binding, not on the signature: the signature
 * alone proves only possession of *some* CA-vetted key. See [IssuerSignedJwt.crossBind].
 */
class AdhocTransactionMetadataVerifier(
    private val trustListService: TrustListService,
    private val keySetResolver: IssuerKeySetResolver,
) {
    /**
     * @param jwt the compact JWT from the entry's `metadata` parameter
     * @param entryType the `type` of the enclosing `transaction_data` entry (§5.3 step 7)
     * @param credential the PaSO Credential the entry targets
     */
    suspend fun verify(
        jwt: String,
        entryType: String,
        credential: Credential,
        now: Instant = Instant.now(),
    ): Result<TransactionDataTypeMetadata> =
        runCatching {
            val signed = SignedJWT.parse(jwt)

            // §5.3.1 — typ MUST be adhoc-transaction-metadata+jwt. Checked before anything
            // else so a credential-metadata+jwt replayed into this slot is refused on sight
            // rather than being accepted for its (differently-scoped) contents.
            val typ = signed.header.type?.type
            check(typ == EXPECTED_TYP) { "$LABEL has typ=$typ; expected $EXPECTED_TYP" }

            // §5.3.2/§5.3.3 — the key is established by the mechanism that verified THIS
            // credential, never by this JWT's header. This JWT comes from the Relying
            // Party (§5.5), so letting its header pick the rule would hand mechanism
            // selection to the least trusted party in the exchange (sd-jwt-vc §10.2).
            val binding =
                credential.issuerBinding
                    ?: error(
                        "credential ${credential.id} has no recorded issuer binding; " +
                            "ad-hoc metadata cannot be bound to it",
                    )

            val chain =
                when (binding) {
                    IssuerBinding.X5c -> {
                        val c = IssuerSignedJwt.readX5cChain(signed, LABEL)
                        IssuerSignedJwt.validateChain(c, now, LABEL)
                        IssuerSignedJwt.verifySignature(signed, c.first(), LABEL)
                        c
                    }

                    IssuerBinding.KeySet -> {
                        null
                    }
                }

            val payload =
                HttpClientFactory.json.decodeFromString(
                    AdhocMetadataPayloadDto.serializer(),
                    signed.payload.toString(),
                )

            check(trustListService.isIssuerTrusted(payload.iss, chain)) {
                "$LABEL issuer ${payload.iss} not trusted (or leaf fingerprint mismatch)"
            }

            // §5.3.4, §5.3.5, §5.3.6 (sub bullet), §5.3.7
            checkPayloadClaims(
                payload = payload,
                credentialIssuerId = credential.issuerId,
                credentialTypeIdentifier = credential.configurationId,
                entryType = entryType,
                now = now,
            )

            // §5.3.6 — credential binding. This is the check that turns "signed by someone
            // a CA vetted" (or "signed by someone in some key set") into "signed by THIS
            // credential's issuer". Which of the two rules applies was fixed above.
            when (binding) {
                IssuerBinding.X5c -> {
                    val credentialChain =
                        IssuerSignedJwt.credentialChain(credential)
                            ?: error("credential ${credential.id} has no x5c chain to cross-bind against")
                    IssuerSignedJwt.crossBind(
                        jwtChain = requireNotNull(chain),
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

            toMetadata(payload)
        }

    companion object {
        private const val EXPECTED_TYP = "adhoc-transaction-metadata+jwt"
        private const val LABEL = "ad-hoc metadata JWT"

        /**
         * The device-signed namespace from PaSO Core §6.3. §5.2 calls out explicitly that
         * this is *not* a credential type identifier and **SHALL NOT** be accepted as `sub`.
         * It is rejected by name rather than left to the equality check below, so the
         * prohibition survives a future credential whose `configurationId` happens to
         * collide with it.
         */
        private const val DEVICE_SIGNED_NAMESPACE = "urn:paso:sca:1"

        /**
         * The payload-only checks of §5.3, pure over decoded input so they are testable
         * on the JVM without minting a certificate hierarchy. Throws [IllegalStateException]
         * naming the failed step; callers run this inside `runCatching`.
         */
        internal fun checkPayloadClaims(
            payload: AdhocMetadataPayloadDto,
            credentialIssuerId: String,
            credentialTypeIdentifier: String,
            entryType: String,
            now: Instant,
        ) {
            // §5.3.4 — iss matches the targeted credential's issuer identifier
            check(payload.iss == credentialIssuerId) {
                "$LABEL iss=${payload.iss} ≠ credential issuerId=$credentialIssuerId"
            }

            // §5.3.5 — exp has not passed. The boundary counts as passed.
            val expInstant = Instant.ofEpochSecond(payload.exp)
            check(now.isBefore(expInstant)) { "$LABEL expired at $expInstant (now=$now)" }

            // §5.3.6 / §7.6 first bullet — sub is the credential's type identifier
            check(payload.sub != DEVICE_SIGNED_NAMESPACE) {
                "$LABEL sub=$DEVICE_SIGNED_NAMESPACE is a device-signed namespace, not a credential type"
            }
            check(payload.sub == credentialTypeIdentifier) {
                "$LABEL sub=${payload.sub} ≠ credential type=$credentialTypeIdentifier"
            }

            // §5.3.7 — transaction_data_type equals the enclosing entry's type
            check(payload.transactionDataType.isNotBlank()) { "$LABEL transaction_data_type is blank" }
            check(payload.transactionDataType == entryType) {
                "$LABEL transaction_data_type=${payload.transactionDataType} ≠ entry type=$entryType"
            }
        }

        /** Decodes the verified payload's `metadata` object (§5.2) to the domain type. */
        internal fun toMetadata(payload: AdhocMetadataPayloadDto): TransactionDataTypeMetadata = payload.metadata.toDomain()
    }
}
