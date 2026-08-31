package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.domain.model.AdhocMetadataPayloadDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Covers the claim-level half of paso-proof-metadata.md §5.3 — steps 4, 5, 7 and the
 * `sub` bullet of step 6 — as implemented by
 * [AdhocTransactionMetadataVerifier.checkPayloadClaims].
 *
 * Steps 2, 3 and the certificate bullets of step 6 are x5c mechanics shared verbatim
 * with the `credential-metadata+jwt` channel via `IssuerSignedJwt`; minting a CA
 * hierarchy to exercise them would need a cert-builder dependency the project does
 * not carry, and they are already exercised in production by the stored-metadata
 * path. What is unique to the ad-hoc channel is entirely in this function, so this
 * is where the tests belong.
 */
class AdhocTransactionMetadataVerifierTest {
    private val entryType = "urn:paso:sca:dev.digitallabor:limitchange:1"
    private val issuerId = "https://foundry.digitallabor.dev"
    private val vct = "https://creds.digitallabor.dev/vct/sparkassen_auth"
    private val now: Instant = Instant.ofEpochSecond(1_760_000_000)

    private fun payload(
        iss: String = issuerId,
        sub: String = vct,
        type: String = entryType,
        exp: Long = now.epochSecond + 3600,
    ): AdhocMetadataPayloadDto =
        HttpClientFactory.json.decodeFromString(
            AdhocMetadataPayloadDto.serializer(),
            """
            {
              "iss": "$iss",
              "sub": "$sub",
              "format": "dc+sd-jwt",
              "iat": ${now.epochSecond - 60},
              "exp": $exp,
              "transaction_data_type": "$type",
              "metadata": {
                "claims": [
                  { "path": ["old_limit"], "mandatory": true, "value_type": "iso_currency_amount",
                    "display": [{ "locale": "en-US", "name": "Old limit" }] }
                ],
                "ui_labels": {
                  "transaction_title": [{ "locale": "en-US", "value": "Change daily limit" }]
                }
              }
            }
            """.trimIndent(),
        )

    private fun check(
        dto: AdhocMetadataPayloadDto,
        expectedType: String = entryType,
    ) = AdhocTransactionMetadataVerifier.checkPayloadClaims(
        payload = dto,
        credentialIssuerId = issuerId,
        credentialTypeIdentifier = vct,
        entryType = expectedType,
        now = now,
    )

    private fun assertRejects(
        message: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected rejection: $message")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should explain the failure, was: ${e.message}",
                !e.message.isNullOrBlank(),
            )
        }
    }

    @Test
    fun acceptsAWellFormedPayload() {
        check(payload())
    }

    @Test
    fun decodesTheMetadataObjectThroughTheSharedMapper() {
        // §5.2 defines `metadata` as a single `transaction_data_types` entry value, so it
        // must land on the same domain type the stored channel produces — that identity is
        // what lets DynamicTransactionDataBlock render both without branching.
        val metadata = AdhocTransactionMetadataVerifier.toMetadata(payload())
        assertEquals(listOf("old_limit"), metadata.claims.single().path)
        assertEquals(
            "Old limit",
            metadata.claims
                .single()
                .display
                .single()
                .name,
        )
        assertEquals(
            "Change daily limit",
            metadata.uiLabels.transactionTitle
                .single()
                .value,
        )
    }

    @Test
    fun rejectsAnIssuerOtherThanTheCredentialsOwn() {
        // §5.3 step 4. A verifier that could name any `iss` could pair a genuine
        // signature from one issuer with a credential from another.
        assertRejects("iss mismatch") { check(payload(iss = "https://attacker.example")) }
    }

    @Test
    fun rejectsAnExpiredJwt() {
        // §5.3 step 5. `exp` is what bounds how long a Relying Party may cache and
        // replay an ad-hoc JWT (§5.2), so an expired one must not render.
        assertRejects("exp passed") { check(payload(exp = now.epochSecond - 1)) }
    }

    @Test
    fun rejectsAnExpiryExactlyAtNow() {
        // "has not passed" — treat the boundary as passed rather than valid.
        assertRejects("exp == now") { check(payload(exp = now.epochSecond)) }
    }

    @Test
    fun rejectsASubThatIsNotTheCredentialsTypeIdentifier() {
        // §5.3 step 6 / §7 step 6, first bullet.
        assertRejects("sub mismatch") { check(payload(sub = "https://creds.digitallabor.dev/vct/other")) }
    }

    @Test
    fun rejectsTheDeviceSignedNamespaceAsSub() {
        // §5.2 is explicit that `urn:paso:sca:1` is NOT a credential type identifier and
        // SHALL NOT be accepted as `sub`. It would otherwise be a tempting value for an
        // implementer reading PaSO Core §6.3 — and one no credential's vct will match.
        assertRejects("sub is the device-signed namespace") { check(payload(sub = "urn:paso:sca:1")) }
    }

    @Test
    fun rejectsATransactionDataTypeThatDisagreesWithTheEnclosingEntry() {
        // §5.3 step 7. Without this, metadata legitimately issued for a low-stakes type
        // could be attached to an entry of a different, higher-stakes type — relabelling
        // what the user is actually approving.
        assertRejects("type mismatch") {
            check(payload(type = "urn:paso:sca:dev.digitallabor:login:1"))
        }
    }

    @Test
    fun rejectsAnEmptyTransactionDataType() {
        assertRejects("blank type") { check(payload(type = "")) }
    }
}
