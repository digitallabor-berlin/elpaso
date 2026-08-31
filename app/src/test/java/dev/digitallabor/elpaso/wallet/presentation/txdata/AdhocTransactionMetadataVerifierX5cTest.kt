package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

/**
 * The certificate half of paso-proof-metadata.md §5.3 — steps 2, 3 and the certificate
 * bullets of step 6 — which [AdhocTransactionMetadataVerifierTest] deliberately does not
 * cover because it exercises only the pure claim checks.
 */
class AdhocTransactionMetadataVerifierX5cTest {
    private val now = TestPki.NOW
    private val entryType = "urn:paso:sca:dev.digitallabor:limitchange:1"
    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val trustList: TrustListService =
        mockk {
            every { isIssuerTrusted(any(), any()) } returns true
        }
    private val verifier = AdhocTransactionMetadataVerifier(trustList)

    private val credential =
        TestCredentials.sdJwt(
            issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", chain),
        )

    private fun payload(type: String = entryType) =
        """
        {
          "iss": "${TestCredentials.ISSUER_ID}",
          "sub": "${TestCredentials.VCT}",
          "format": "dc+sd-jwt",
          "iat": ${now.epochSecond - 60},
          "exp": ${now.epochSecond + 3600},
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
        """.trimIndent()

    private fun jwt(
        typ: String = "adhoc-transaction-metadata+jwt",
        payloadJson: String = payload(),
        jwtChain: List<X509Certificate>? = chain,
        signer: TestPki.Node = leaf,
    ) = TestPki.jws(signer = signer, typ = typ, payloadJson = payloadJson, chain = jwtChain)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `happy path verifies and decodes`() {
        val result = verifier.verify(jwt(), entryType, credential, now)
        assertTrue("expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, result.getOrThrow().claims.size)
    }

    @Test
    fun `a credential-metadata JWT replayed into this slot is refused on typ`() {
        val message = failureMessage(verifier.verify(jwt(typ = "credential-metadata+jwt"), entryType, credential, now))
        assertTrue(message, message.contains("typ="))
    }

    @Test
    fun `missing x5c is rejected`() {
        val message = failureMessage(verifier.verify(jwt(jwtChain = null), entryType, credential, now))
        assertTrue(message, message.contains("missing x5c header"))
    }

    @Test
    fun `signature by a foreign key is rejected`() {
        val foreign = TestPki.child("CN=Issuer Leaf", root)
        val forged = TestPki.jws(foreign, "adhoc-transaction-metadata+jwt", payload(), chain)
        val message = failureMessage(verifier.verify(forged, entryType, credential, now))
        assertTrue(message, message.contains("signature verification"))
    }

    @Test
    fun `untrusted issuer is rejected`() {
        every { trustList.isIssuerTrusted(any(), any()) } returns false
        val message = failureMessage(verifier.verify(jwt(), entryType, credential, now))
        assertTrue(message, message.contains("not trusted"))
    }

    @Test
    fun `cross-bind root mismatch is rejected`() {
        val otherRoot = TestPki.ca("CN=Other Root")
        val otherLeaf = TestPki.child("CN=Issuer Leaf", otherRoot)
        val forged =
            TestPki.jws(
                otherLeaf,
                "adhoc-transaction-metadata+jwt",
                payload(),
                TestPki.chain(otherLeaf, otherRoot),
            )
        val message = failureMessage(verifier.verify(forged, entryType, credential, now))
        assertTrue(message, message.contains("root CA does not match"))
    }

    @Test
    fun `transaction_data_type mismatch is rejected`() {
        val message =
            failureMessage(
                verifier.verify(jwt(payloadJson = payload(type = "urn:paso:sca:other:1")), entryType, credential, now),
            )
        assertTrue(message, message.contains("transaction_data_type"))
    }
}
