package dev.digitallabor.elpaso.wallet.issuance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The issuance gate surfaces through `IssuanceClient.State.Failed`, whose message is
 * built by `describeIssuanceFailure` walking the cause chain. This pins the two
 * properties that walk depends on: the exception is findable by type through a wrapper,
 * and it carries a human-readable reason distinct from its own message.
 */
class CredentialSignatureRejectedTest {
    @Test
    fun `the reason is preserved separately from the message`() {
        val rejection = CredentialSignatureRejected("x5c chain link 0 to 1 fails verification", null)
        assertEquals("x5c chain link 0 to 1 fails verification", rejection.reason)
        assertTrue(rejection.message!!.contains("x5c chain link 0 to 1 fails verification"))
    }

    @Test
    fun `it is findable through a wrapping exception`() {
        val rejection = CredentialSignatureRejected("kid names no published key", null)
        val wrapped: Throwable = IllegalStateException("issueOne failed", rejection)
        val found =
            generateSequence(wrapped) { it.cause }
                .firstNotNullOfOrNull { it as? CredentialSignatureRejected }
        assertEquals(rejection, found)
    }

    @Test
    fun `the underlying cause is retained for the log`() {
        val root = IllegalStateException("signature verification returned false")
        val rejection = CredentialSignatureRejected("signature invalid", root)
        assertEquals(root, rejection.cause)
    }
}
