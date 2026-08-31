package dev.digitallabor.elpaso.wallet.vct

import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdJwtHeaderReaderTest {
    private val root = TestPki.ca("CN=Reader Root")
    private val leaf = TestPki.child("CN=Reader Leaf", root)

    private fun sdJwt(withX5c: Boolean): ByteArray {
        val jwt =
            TestPki.jws(
                signer = leaf,
                typ = "dc+sd-jwt",
                payloadJson = """{"iss":"https://issuer.example","vct":"https://vct.example/x"}""",
                chain = if (withX5c) TestPki.chain(leaf, root) else null,
            )
        return "$jwt~".toByteArray()
    }

    @Test
    fun `issuerJwt returns everything before the first tilde`() {
        val payload = sdJwt(withX5c = true)
        val issuerJwt = SdJwtHeaderReader.issuerJwt(payload)
        assertEquals(payload.decodeToString().substringBefore('~'), issuerJwt)
    }

    @Test
    fun `issuerJwt is null for a blank payload`() {
        assertNull(SdJwtHeaderReader.issuerJwt("~~".toByteArray()))
    }

    @Test
    fun `extractX5c decodes the chain leaf first`() {
        val chain = SdJwtHeaderReader.extractX5c(sdJwt(withX5c = true))
        assertEquals(2, chain?.size)
        assertEquals("CN=Reader Leaf", chain!![0].subjectX500Principal.name)
        assertEquals("CN=Reader Root", chain[1].subjectX500Principal.name)
    }

    @Test
    fun `extractX5c is null when the header carries no x5c`() {
        assertNull(SdJwtHeaderReader.extractX5c(sdJwt(withX5c = false)))
    }

    @Test
    fun `extractX5c is null for a non-JWT payload`() {
        assertNull(SdJwtHeaderReader.extractX5c("not-a-jwt~".toByteArray()))
    }
}
