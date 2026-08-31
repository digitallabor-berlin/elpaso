package dev.digitallabor.elpaso.wallet.issuance

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
 * paso-proof-metadata.md §6 under the key-set mechanism — the branch this whole spec
 * exists to enable.
 *
 * The test that matters most is the mechanism-confusion pair: a metadata JWT's own
 * header must not be able to select which binding rule the wallet applies. The dispatch
 * key is the credential's recorded mechanism (sd-jwt-vc §10.2).
 */
class CredentialMetadataVerifierKeySetTest {
    private val now: Instant = TestPki.NOW
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

    private fun verifier(set: IssuerKeySet? = keySet) = CredentialMetadataVerifier(trustList, resolver(set))

    private fun credential(
        binding: IssuerBinding? = IssuerBinding.KeySet,
        source: String? = sourceUrl,
    ) = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
        issuerBinding = binding,
        issuerKeySetSource = source,
    )

    private fun metadataPayload() =
        """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
          "credential_metadata_uri": "https://issuer.example/credential-metadata",
          "credential_metadata": {
            "display": [{ "locale": "en-US", "name": "Test Credential" }]
          }
        }
        """.trimIndent()

    private fun metadataJwt(
        kid: String? = "k1",
        withX5c: Boolean = false,
    ): String {
        val root = TestPki.ca("CN=Unrelated Root")
        return TestPki.jws(
            signer = signingNode,
            typ = "credential-metadata+jwt",
            payloadJson = metadataPayload(),
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
            val result = verifier().verify(metadataJwt(), credential(), now)
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        }

    @Test
    fun `a cache miss fails rather than fetching`() =
        runTest {
            val message = failureMessage(verifier(set = null).verify(metadataJwt(), credential(), now))
            assertTrue(message, message.contains("no cached issuer key set"))
        }

    @Test
    fun `a kid naming no published key is rejected`() =
        runTest {
            val message = failureMessage(verifier().verify(metadataJwt(kid = "other"), credential(), now))
            assertTrue(message, message.contains("kid=other"))
        }

    @Test
    fun `a key set from a different source URL is rejected`() =
        runTest {
            val message =
                failureMessage(
                    verifier().verify(metadataJwt(), credential(source = "https://issuer.example/keys.json"), now),
                )
            assertTrue(message, message.contains("different issuer key set"))
        }

    @Test
    fun `an x5c header on the metadata JWT cannot switch a key-set credential to the cert rule`() =
        runTest {
            // §10.2, one level up. Under a key-set credential the x5c is a confusion attempt,
            // not an alternative route — even though the chain it presents is internally valid.
            val message = failureMessage(verifier().verify(metadataJwt(withX5c = true), credential(), now))
            assertTrue(message, message.contains("mechanism confusion"))
        }

    @Test
    fun `a kid-only metadata JWT cannot switch an x5c credential to the key-set rule`() =
        runTest {
            // The mirror case: the credential was verified by x5c, so §7 step 6's certificate
            // bullet applies and a JWT with no chain must fail there rather than fall through.
            val message =
                failureMessage(
                    verifier().verify(metadataJwt(), credential(binding = IssuerBinding.X5c, source = null), now),
                )
            assertTrue(message, message.contains("missing x5c header"))
        }

    @Test
    fun `a credential with no recorded binding is refused outright`() =
        runTest {
            val message =
                failureMessage(
                    verifier().verify(metadataJwt(), credential(binding = null, source = null), now),
                )
            assertTrue(message, message.contains("no recorded issuer binding"))
        }
}
