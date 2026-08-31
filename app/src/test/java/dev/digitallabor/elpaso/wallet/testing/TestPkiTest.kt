package dev.digitallabor.elpaso.wallet.testing

import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class TestPkiTest {
    @Test
    fun `chain is leaf first and each link verifies against its parent`() {
        val root = TestPki.ca("CN=Test Root")
        val intermediate = TestPki.child("CN=Test Intermediate", root, isCa = true)
        val leaf = TestPki.child("CN=Test Leaf", intermediate)
        val chain = TestPki.chain(leaf, intermediate, root)

        assertEquals(3, chain.size)
        assertEquals("CN=Test Leaf", chain[0].subjectX500Principal.name)
        assertEquals("CN=Test Root", chain[2].subjectX500Principal.name)
        chain[0].verify(chain[1].publicKey)
        chain[1].verify(chain[2].publicKey)
    }

    @Test
    fun `jws carries the x5c header and verifies against the leaf`() {
        val root = TestPki.ca("CN=Test Root")
        val leaf = TestPki.child("CN=Test Leaf", root)
        val jwt =
            TestPki.jws(
                signer = leaf,
                typ = "example+jwt",
                payloadJson = """{"iss":"https://example.com"}""",
                chain = TestPki.chain(leaf, root),
            )
        val signed = SignedJWT.parse(jwt)
        assertEquals("example+jwt", signed.header.type.type)
        assertEquals(2, signed.header.x509CertChain.size)
        assertTrue(
            signed.verify(
                com.nimbusds.jose.crypto
                    .ECDSAVerifier(TestPki.jwk(leaf).toECKey()),
            ),
        )
    }

    @Test
    fun `an expired leaf is expired at the fixed clock`() {
        val root = TestPki.ca("CN=Test Root")
        val expired =
            TestPki.child(
                subject = "CN=Expired Leaf",
                parent = root,
                notBefore = TestPki.NOW.minusSeconds(7_200),
                notAfter = TestPki.NOW.minusSeconds(3_600),
            )
        try {
            expired.certificate.checkValidity(Date.from(TestPki.NOW))
            org.junit.Assert.fail("expected CertificateExpiredException")
        } catch (e: java.security.cert.CertificateExpiredException) {
            assertTrue(true)
        }
    }

    @Test
    fun `jwk exposes the requested kid`() {
        val root = TestPki.ca("CN=Test Root")
        val leaf = TestPki.child("CN=Test Leaf", root)
        assertEquals("k1", TestPki.jwk(leaf, kid = "k1").keyID)
    }
}
