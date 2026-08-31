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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * paso-proof-metadata.md §6 end to end over a real certificate hierarchy: the happy
 * path, then each step failing in isolation.
 */
class CredentialMetadataVerifierX5cTest {
    private val now = TestPki.NOW
    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val trustList: TrustListService =
        mockk {
            every { isIssuerTrusted(any(), any()) } returns true
        }

    /** The x5c path must never resolve a key set; a success here would be a bug. */
    private val noKeySets =
        object : IssuerKeySetResolver {
            override suspend fun resolve(
                issuerId: String,
                now: java.time.Instant,
            ): Result<IssuerKeySet> = Result.failure(IllegalStateException("the x5c path must not resolve a key set"))
        }
    private val verifier = CredentialMetadataVerifier(trustList, noKeySets)

    private val credential =
        TestCredentials.sdJwt(
            issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", chain),
            issuerBinding = IssuerBinding.X5c,
        )

    private fun metadataPayload(
        iss: String = TestCredentials.ISSUER_ID,
        sub: String = TestCredentials.VCT,
        exp: Long = now.epochSecond + 3600,
    ) = """
        {
          "iss": "$iss",
          "sub": "$sub",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": $exp,
          "credential_metadata_uri": "https://issuer.example/credential-metadata",
          "credential_metadata": {
            "display": [{ "locale": "en-US", "name": "Test Credential" }]
          }
        }
        """.trimIndent()

    private fun metadataJwt(
        typ: String = "credential-metadata+jwt",
        payloadJson: String = metadataPayload(),
        jwtChain: List<X509Certificate>? = chain,
        signer: TestPki.Node = leaf,
    ) = TestPki.jws(signer = signer, typ = typ, payloadJson = payloadJson, chain = jwtChain)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies and decodes`() =
        runTest {
            val result = verifier.verify(metadataJwt(), credential, now)
            assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(now.epochSecond + 3600, result.getOrThrow().exp)
        }

    @Test
    fun `wrong typ is rejected before anything else`() =
        runTest {
            val message = failureMessage(verifier.verify(metadataJwt(typ = "adhoc-transaction-metadata+jwt"), credential, now))
            assertTrue(message, message.contains("typ="))
        }

    @Test
    fun `missing x5c is rejected`() =
        runTest {
            val message = failureMessage(verifier.verify(metadataJwt(jwtChain = null), credential, now))
            assertTrue(message, message.contains("missing x5c header"))
        }

    @Test
    fun `signature by a foreign key is rejected`() =
        runTest {
            val foreign = TestPki.child("CN=Issuer Leaf", root)
            // Signed by `foreign`, but presenting the genuine leaf's chain.
            val jwt = TestPki.jws(signer = foreign, typ = "credential-metadata+jwt", payloadJson = metadataPayload(), chain = chain)
            val message = failureMessage(verifier.verify(jwt, credential, now))
            assertTrue(message, message.contains("signature verification"))
        }

    @Test
    fun `untrusted issuer is rejected`() =
        runTest {
            every { trustList.isIssuerTrusted(any(), any()) } returns false
            val message = failureMessage(verifier.verify(metadataJwt(), credential, now))
            assertTrue(message, message.contains("not trusted"))
        }

    @Test
    fun `iss mismatch is rejected`() =
        runTest {
            val message =
                failureMessage(
                    verifier.verify(metadataJwt(payloadJson = metadataPayload(iss = "https://other.example")), credential, now),
                )
            assertTrue(message, message.contains("≠ credential issuerId"))
        }

    @Test
    fun `expired metadata is rejected`() =
        runTest {
            val message =
                failureMessage(
                    verifier.verify(metadataJwt(payloadJson = metadataPayload(exp = now.epochSecond - 1)), credential, now),
                )
            assertTrue(message, message.contains("expired"))
        }

    @Test
    fun `sub mismatch is rejected`() =
        runTest {
            val message =
                failureMessage(
                    verifier.verify(metadataJwt(payloadJson = metadataPayload(sub = "https://vct.example/other")), credential, now),
                )
            assertTrue(message, message.contains("≠ credential"))
        }

    @Test
    fun `cross-bind root mismatch is rejected`() =
        runTest {
            val otherRoot = TestPki.ca("CN=Other Root")
            val otherLeaf = TestPki.child("CN=Issuer Leaf", otherRoot)
            val jwt = TestPki.jws(otherLeaf, "credential-metadata+jwt", metadataPayload(), TestPki.chain(otherLeaf, otherRoot))
            val message = failureMessage(verifier.verify(jwt, credential, now))
            assertTrue(message, message.contains("root CA does not match"))
        }

    @Test
    fun `cross-bind leaf subject mismatch is rejected`() =
        runTest {
            val otherLeaf = TestPki.child("CN=Different Leaf", root)
            val jwt = TestPki.jws(otherLeaf, "credential-metadata+jwt", metadataPayload(), TestPki.chain(otherLeaf, root))
            val message = failureMessage(verifier.verify(jwt, credential, now))
            assertTrue(message, message.contains("leaf subject"))
        }
}
