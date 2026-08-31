package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The x5c mechanics shared by `CredentialMetadataVerifier` (paso-proof-metadata.md §6)
 * and `AdhocTransactionMetadataVerifier` (§5.3). Every check in [IssuerSignedJwt]
 * throws on failure by design, so each negative case asserts on the thrown message.
 */
class IssuerSignedJwtX5cTest {
    private val now = TestPki.NOW
    private val root = TestPki.ca("CN=Root A")
    private val intermediate = TestPki.child("CN=Intermediate A", root, isCa = true)
    private val leaf = TestPki.child("CN=Leaf A", intermediate)
    private val fullChain = TestPki.chain(leaf, intermediate, root)

    private fun signed(
        chain: List<java.security.cert.X509Certificate>? = fullChain,
        signer: TestPki.Node = leaf,
    ) = SignedJWT.parse(
        TestPki.jws(
            signer = signer,
            typ = "credential-metadata+jwt",
            payloadJson = """{"iss":"x"}""",
            chain = chain,
        ),
    )

    private inline fun expectFailure(
        fragment: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected IllegalStateException containing \"$fragment\"")
        } catch (e: IllegalStateException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
        }
    }

    @Test
    fun `readX5cChain returns the chain leaf first`() {
        val chain = IssuerSignedJwt.readX5cChain(signed(), "L")
        assertEquals(3, chain.size)
        assertEquals("CN=Leaf A", chain[0].subjectX500Principal.name)
    }

    @Test
    fun `readX5cChain rejects a missing x5c header`() {
        expectFailure("missing x5c header") { IssuerSignedJwt.readX5cChain(signed(chain = null), "L") }
    }

    @Test
    fun `validateChain accepts a well-linked chain`() {
        IssuerSignedJwt.validateChain(fullChain, now, "L")
    }

    @Test
    fun `validateChain rejects a broken link`() {
        val otherRoot = TestPki.ca("CN=Root B")
        val spliced = listOf(leaf.certificate, otherRoot.certificate)
        expectFailure("x5c link 0→1 fails verification") {
            IssuerSignedJwt.validateChain(spliced, now, "L")
        }
    }

    @Test
    fun `validateChain rejects an expired leaf`() {
        val expiredLeaf =
            TestPki.child(
                subject = "CN=Leaf A",
                parent = intermediate,
                notBefore = now.minusSeconds(7_200),
                notAfter = now.minusSeconds(3_600),
            )
        try {
            IssuerSignedJwt.validateChain(
                TestPki.chain(expiredLeaf, intermediate, root),
                now,
                "L",
            )
            fail("expected CertificateExpiredException")
        } catch (e: java.security.cert.CertificateExpiredException) {
            assertTrue(true)
        }
    }

    @Test
    fun `verifySignature accepts the signing leaf`() {
        IssuerSignedJwt.verifySignature(signed(), fullChain.first(), "L")
    }

    @Test
    fun `verifySignature rejects a foreign leaf`() {
        val foreign = TestPki.child("CN=Foreign Leaf", root)
        expectFailure("signature verification") {
            IssuerSignedJwt.verifySignature(signed(), foreign.certificate, "L")
        }
    }

    @Test
    fun `crossBind accepts same root and same leaf subject`() {
        // A dedicated metadata-signing leaf with the SAME subject under the SAME root,
        // which §7 step 6 permits: the binding does not demand the same key.
        val metadataLeaf = TestPki.child("CN=Leaf A", intermediate)
        IssuerSignedJwt.crossBind(
            jwtChain = TestPki.chain(metadataLeaf, intermediate, root),
            credentialChain = fullChain,
            label = "L",
        )
    }

    @Test
    fun `crossBind rejects a different root`() {
        val otherRoot = TestPki.ca("CN=Root B")
        val otherLeaf = TestPki.child("CN=Leaf A", otherRoot)
        expectFailure("root CA does not match") {
            IssuerSignedJwt.crossBind(
                jwtChain = TestPki.chain(otherLeaf, otherRoot),
                credentialChain = fullChain,
                label = "L",
            )
        }
    }

    @Test
    fun `crossBind rejects a different leaf subject`() {
        val otherLeaf = TestPki.child("CN=Other Leaf", intermediate)
        expectFailure("leaf subject") {
            IssuerSignedJwt.crossBind(
                jwtChain = TestPki.chain(otherLeaf, intermediate, root),
                credentialChain = fullChain,
                label = "L",
            )
        }
    }

    @Test
    fun `crossBind rejects an empty credential chain`() {
        expectFailure("credential x5c chain is empty") {
            IssuerSignedJwt.crossBind(jwtChain = fullChain, credentialChain = emptyList(), label = "L")
        }
    }

    @Test
    fun `credentialChain reads the chain out of an SD-JWT-VC payload`() {
        val credential =
            TestCredentials.sdJwt(
                issuerJwt = TestPki.jws(leaf, "dc+sd-jwt", """{"iss":"https://issuer.example"}""", fullChain),
            )
        val chain = IssuerSignedJwt.credentialChain(credential)
        assertEquals(3, chain?.size)
        assertEquals("CN=Leaf A", chain!!.first().subjectX500Principal.name)
    }
}
