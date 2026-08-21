package dev.digitallabor.elpaso.wallet.issuance

import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestError
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestException
import eu.europa.ec.eudi.openid4vci.CredentialOfferRequestValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CredentialOfferRequestException` extends `Exception()` — no message, no cause — and hides the
 * real failure in `error`, whose variants each hold it in a `reason` field. Walking
 * [Throwable.cause] alone therefore reaches nothing, which is exactly how an unreadable offer
 * ends up reported as a bare "Failed to resolve offer".
 */
class OfferFailureChainTest {
    @Test
    fun `follows the reason field of an offer-resolution error, not just cause`() {
        val root = IllegalStateException("Failed to parse literal '\"ES256\"' as an int value")
        val thrown =
            CredentialOfferRequestException(
                CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata(
                    CredentialIssuerMetadataError.NonParseableCredentialIssuerMetadata(root),
                ),
            )

        val chain = thrown.offerFailureChain()

        assertTrue(
            "chain must reach NonParseableCredentialIssuerMetadata, got ${chain.map { it::class.simpleName }}",
            chain.any { it is CredentialIssuerMetadataError.NonParseableCredentialIssuerMetadata },
        )
        assertTrue("chain must reach the root cause", chain.any { it === root })
    }

    @Test
    fun `deepest message skips the message-less offer exception wrapper`() {
        val thrown =
            CredentialOfferRequestException(
                CredentialOfferRequestError.UnableToResolveCredentialIssuerMetadata(
                    CredentialIssuerMetadataError.NonParseableCredentialIssuerMetadata(
                        IllegalStateException("the real problem"),
                    ),
                ),
            )

        assertNull("precondition: the wrapper carries no message", thrown.message)
        assertEquals("the real problem", thrown.deepestMessage())
    }

    @Test
    fun `still follows an ordinary cause chain`() {
        val thrown = RuntimeException("outer", IllegalArgumentException("inner"))

        assertEquals(2, thrown.offerFailureChain().size)
        assertEquals("inner", thrown.deepestMessage())
    }

    @Test
    fun `deepest message is null when nothing in the chain has one`() {
        // OneOfCredentialOfferOrCredentialOfferUri carries no `reason`, so the walk must stop
        // cleanly at the message-less wrapper rather than inventing text.
        val thrown =
            CredentialOfferRequestException(
                CredentialOfferRequestValidationError.OneOfCredentialOfferOrCredentialOfferUri,
            )

        assertEquals(1, thrown.offerFailureChain().size)
        assertNull(thrown.deepestMessage())
    }

    @Test
    fun `a self-referential cause does not loop forever`() {
        val looping =
            object : RuntimeException("loop") {
                override val cause: Throwable get() = this
            }

        // Terminates, and does not report the same instance twice.
        assertEquals(1, looping.offerFailureChain().size)
    }
}
