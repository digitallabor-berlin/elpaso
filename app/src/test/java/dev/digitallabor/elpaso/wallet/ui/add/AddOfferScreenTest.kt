package dev.digitallabor.elpaso.wallet.ui.add

import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the screen-selection rules of `AddOfferFlow`.
 *
 * These lived inline in the composable as two booleans that conflated [IssuanceClient.State.Idle]
 * with "a resolve is in flight". That produced a dead end: a deep-linked offer that failed left
 * `Failed` on screen, dismissing the modal called `reset()` back to `Idle`, and `Idle` on that
 * route rendered "Resolving credential offer…" forever — with no request in flight, no timeout to
 * fire, and no way to retry, because `LaunchedEffect(incomingOfferUri)` keeps the same key.
 *
 * Extracted as pure functions because the project has no Compose UI test dependency, and because
 * `testOptions.unitTests.isReturnDefaultValues` makes anything touching `android.util.*`
 * untestable on the JVM anyway.
 */
class AddOfferScreenTest {
    // --- the regression that motivated the extraction ---

    @Test
    fun `dismissing a failed deep-linked offer leaves the screen instead of stranding the user`() {
        assertTrue(
            "a deep-linked offer has no scanner to fall back to, so dismissing must navigate away",
            dismissLeavesScreen(hasIncomingOffer = true),
        )
    }

    @Test
    fun `dismissing a failed scan stays put so the user can scan again`() {
        assertFalse(
            "the user came from the scanner, so dismissing should return them to it",
            dismissLeavesScreen(hasIncomingOffer = false),
        )
    }

    @Test
    fun `only the Resolving state reports the resolving screen`() {
        val everythingElse =
            listOf(
                IssuanceClient.State.Failed("boom", null, IssuanceClient.State.Failed.Phase.Offer),
                IssuanceClient.State.Failed("boom", null, IssuanceClient.State.Failed.Phase.Issuance),
                IssuanceClient.State.OfferResolved(
                    "https://issuer.example",
                    emptyList(),
                    IssuanceClient.GrantOption.AuthorizationCode,
                    true,
                ),
                IssuanceClient.State.Issuing,
                IssuanceClient.State.Done(emptyList()),
            )

        for (state in everythingElse) {
            for (hasIncomingOffer in listOf(true, false)) {
                assertTrue(
                    "$state (hasIncomingOffer=$hasIncomingOffer) must not claim to be resolving",
                    addOfferMode(state, hasIncomingOffer) != AddOfferMode.Resolving,
                )
            }
        }
    }

    @Test
    fun `Resolving reports the resolving screen on either entry point`() {
        assertEquals(AddOfferMode.Resolving, addOfferMode(IssuanceClient.State.Resolving, hasIncomingOffer = true))
        assertEquals(AddOfferMode.Resolving, addOfferMode(IssuanceClient.State.Resolving, hasIncomingOffer = false))
    }

    // --- entry-point-dependent surfaces ---

    @Test
    fun `Idle opens the scanner when the user came to scan`() {
        assertEquals(AddOfferMode.Scanner, addOfferMode(IssuanceClient.State.Idle, hasIncomingOffer = false))
    }

    @Test
    fun `Idle on a deep-linked offer shows the resolving screen as the pre-start frame`() {
        // resolveOfferAsync flips the state to Resolving synchronously, so this is only the single
        // frame between composition and the LaunchedEffect running. Rendering the scanner here
        // would flash the camera open and immediately unbind it.
        assertEquals(AddOfferMode.Resolving, addOfferMode(IssuanceClient.State.Idle, hasIncomingOffer = true))
    }

    @Test
    fun `a failed deep-linked offer shows the error surface, not a camera the user never asked for`() {
        val failed = IssuanceClient.State.Failed("boom", null, IssuanceClient.State.Failed.Phase.Offer)

        assertEquals(AddOfferMode.Error, addOfferMode(failed, hasIncomingOffer = true))
    }

    @Test
    fun `a failed scan keeps the scanner behind the modal`() {
        val failed = IssuanceClient.State.Failed("boom", null, IssuanceClient.State.Failed.Phase.Offer)

        assertEquals(AddOfferMode.Scanner, addOfferMode(failed, hasIncomingOffer = false))
    }

    // --- the rest of the flow is entry-point independent ---

    @Test
    fun `remaining states map to their own surfaces`() {
        val resolved =
            IssuanceClient.State.OfferResolved(
                issuer = "https://issuer.example",
                configurations = emptyList(),
                grant = IssuanceClient.GrantOption.AuthorizationCode,
                trusted = true,
            )

        assertEquals(AddOfferMode.Consent, addOfferMode(resolved, hasIncomingOffer = true))
        assertEquals(AddOfferMode.Issuing, addOfferMode(IssuanceClient.State.Issuing, hasIncomingOffer = true))
        assertEquals(AddOfferMode.Finished, addOfferMode(IssuanceClient.State.Done(emptyList()), hasIncomingOffer = true))
    }
}
