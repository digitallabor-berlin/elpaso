package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * paso-proof-metadata.md §5.3 under the key-set mechanism. The ad-hoc channel is the one
 * a Relying Party controls, so the mechanism-confusion cases matter more here than
 * anywhere else in the codebase: this JWT arrives from an untrusted party by design.
 */
class AdhocTransactionMetadataVerifierKeySetTest {
    private val now: Instant = TestPki.NOW
    private val entryType = "urn:paso:sca:dev.digitallabor:limitchange:1"
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"

    private val signingNode = TestPki.ca("CN=Metadata Signer")
    private val signingJwk = TestPki.jwk(signingNode, kid = "k1")

    private val keySet =
        IssuerKeySet.parse(
            issuer = TestCredentials.ISSUER_ID,
            jwksJson = """{"keys":[${signingJwk.toJSONString()}]}""",
            sourceUrl = sourceUrl,
            fetchedAt = now,
        )

    private fun resolver(set: IssuerKeySet? = keySet) =
        object : IssuerKeySetResolver {
            override suspend fun resolve(
                issuerId: String,
                now: Instant,
            ): Result<IssuerKeySet> =
                set?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("no cached issuer key set for $issuerId"))
        }

    private val trustList: TrustListService =
        mockk {
            every { isIssuerTrusted(any(), any()) } returns true
        }

    private fun verifier(set: IssuerKeySet? = keySet) = AdhocTransactionMetadataVerifier(trustList, resolver(set))

    private fun credential(
        binding: IssuerBinding? = IssuerBinding.KeySet,
        source: String? = sourceUrl,
    ) = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
        issuerBinding = binding,
        issuerKeySetSource = source,
    )

    private fun payload() =
        """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
          "transaction_data_type": "$entryType",
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
        """.trimIndent()

    private fun jwt(
        kid: String? = "k1",
        withX5c: Boolean = false,
    ): String {
        val root = TestPki.ca("CN=Unrelated Root")
        return TestPki.jws(
            signer = signingNode,
            typ = "adhoc-transaction-metadata+jwt",
            payloadJson = payload(),
            chain = if (withX5c) TestPki.chain(TestPki.child("CN=Unrelated Leaf", root), root) else null,
            kid = kid,
        )
    }

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies against the cached key set`() =
        runTest {
            val result = verifier().verify(jwt(), entryType, credential(), now)
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        }

    @Test
    fun `a cache miss fails rather than fetching mid-presentation`() =
        runTest {
            val message = failureMessage(verifier(set = null).verify(jwt(), entryType, credential(), now))
            assertTrue(message, message.contains("no cached issuer key set"))
        }

    @Test
    fun `a verifier-supplied x5c cannot switch a key-set credential to the cert rule`() =
        runTest {
            val message = failureMessage(verifier().verify(jwt(withX5c = true), entryType, credential(), now))
            assertTrue(message, message.contains("mechanism confusion"))
        }

    @Test
    fun `a kid-only JWT cannot switch an x5c credential to the key-set rule`() =
        runTest {
            val message =
                failureMessage(
                    verifier().verify(jwt(), entryType, credential(binding = IssuerBinding.X5c, source = null), now),
                )
            assertTrue(message, message.contains("missing x5c header"))
        }

    @Test
    fun `a key set from a different source URL is rejected`() =
        runTest {
            val message =
                failureMessage(
                    verifier().verify(jwt(), entryType, credential(source = "https://issuer.example/keys.json"), now),
                )
            assertTrue(message, message.contains("different issuer key set"))
        }

    @Test
    fun `the claim checks still run under the key-set branch`() =
        runTest {
            // The dispatch changes only how the key is found; §5.3's claim checks are untouched.
            val message = failureMessage(verifier().verify(jwt(), "urn:paso:sca:other:1", credential(), now))
            assertTrue(message, message.contains("transaction_data_type"))
        }
}
