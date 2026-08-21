package dev.digitallabor.elpaso.wallet.ui.add

import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient

/**
 * Which surface `AddOfferFlow` shows for a given [IssuanceClient.State].
 *
 * Split out of the composable because the rule that picks it used to be two inline booleans that
 * treated [IssuanceClient.State.Idle] as "a resolve is in flight". Idle is the *absence* of work,
 * so that conflation let the screen claim to be resolving when nothing was — see [addOfferMode].
 */
enum class AddOfferMode {
    /** Live camera, waiting for a QR code. */
    Scanner,

    /** A resolve is genuinely in flight. */
    Resolving,

    /** Offer resolved; showing the consent screen. */
    Consent,

    /** Handed off to the browser for an authorization-code grant. */
    AwaitingBrowser,

    /** Credentials are being issued. */
    Issuing,

    /** Done; the caller navigates away. */
    Finished,

    /** Failure on a deep-linked offer: the modal stands alone, with no camera behind it. */
    Error,
}

/**
 * The surface to show for [state], given whether the user arrived via a deep link
 * ([hasIncomingOffer]) or opened the scanner themselves.
 *
 * The entry point matters only for the two states where the user has a choice to make about what
 * comes next — [IssuanceClient.State.Idle] and [IssuanceClient.State.Failed]. Someone who opened
 * the scanner should land back on the scanner; someone who tapped a link never asked for a camera.
 */
fun addOfferMode(
    state: IssuanceClient.State,
    hasIncomingOffer: Boolean,
): AddOfferMode =
    when (state) {
        // On a deep link this is only the single frame between composition and the LaunchedEffect
        // that starts the resolve — resolveOfferAsync moves to Resolving synchronously. Showing the
        // scanner here would flash the camera open and immediately unbind it.
        IssuanceClient.State.Idle -> if (hasIncomingOffer) AddOfferMode.Resolving else AddOfferMode.Scanner

        IssuanceClient.State.Resolving -> AddOfferMode.Resolving

        is IssuanceClient.State.Failed -> if (hasIncomingOffer) AddOfferMode.Error else AddOfferMode.Scanner

        is IssuanceClient.State.OfferResolved -> AddOfferMode.Consent

        is IssuanceClient.State.AwaitingAuth -> AddOfferMode.AwaitingBrowser

        IssuanceClient.State.Issuing -> AddOfferMode.Issuing

        is IssuanceClient.State.Done -> AddOfferMode.Finished
    }

/**
 * Whether dismissing the error modal should leave the screen entirely.
 *
 * It must, for a deep-linked offer. Dismissing resets the client to [IssuanceClient.State.Idle],
 * and `LaunchedEffect(incomingOfferUri)` will not re-run for an unchanged offer URI, so staying
 * would strand the user on a screen with nothing in flight and no way to retry. A user who came
 * from the scanner, by contrast, has somewhere useful to stay: the scanner.
 */
fun dismissLeavesScreen(hasIncomingOffer: Boolean): Boolean = hasIncomingOffer
