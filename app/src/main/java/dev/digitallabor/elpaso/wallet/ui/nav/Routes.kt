package dev.digitallabor.elpaso.wallet.ui.nav

import android.net.Uri
import dev.digitallabor.elpaso.wallet.dcapi.DcApiSelection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.receiveAsFlow

/** App-level navigation routes. */
sealed interface Route {
    data object Home : Route

    data class Detail(
        val credentialId: String,
    ) : Route

    data object AddScan : Route

    data class OfferConsent(
        val offerUri: String? = null,
    ) : Route

    /**
     * QR scan screen scoped to OpenID4VP presentation requests. Triggered by the
     * fling-up gesture on a credential pass; [preselectedCredentialId] is forwarded
     * to [Present] so that pass is pre-highlighted on the selection screen.
     */
    data class PresentScan(
        val preselectedCredentialId: String,
    ) : Route

    data class Present(
        val rawRequestJson: String,
        val callingPackage: String?,
        /**
         * Credential to pre-highlight, set by the fling-up gesture on a pass. Only
         * meaningful on the deep-link path; the DC API path uses [dcApiSelection].
         */
        val preselectedCredentialId: String? = null,
        /**
         * What the user actually chose in the system DC API selector, when this route was
         * opened by [dev.digitallabor.elpaso.wallet.dcapi.DcPresentationActivity]. Non-null
         * means the choice is already made and the wallet must not ask a second time — see
         * [DcApiSelection] for why this is a set rather than a single id.
         */
        val dcApiSelection: DcApiSelection? = null,
        /**
         * True when Credential Manager / Play Services performed a
         * `BIOMETRIC_STRONG`/`BIOMETRIC` authentication for this DC API request before
         * launching us — i.e. `ProviderGetCredentialRequest.biometricPromptResult.
         * authenticationResult.authenticationType == TYPE_BIOMETRIC`. If true, the
         * wallet can skip its own BiometricPrompt; otherwise it must prompt (the
         * platform won't forward the response back to the verifier without an inline
         * user gesture).
         */
        val systemPreAuthBiometric: Boolean = false,
    ) : Route

    data object Settings : Route
}

/**
 * Nesting level of a route, used to pick the direction of the screen transition.
 * Only [Route.Home] sits at the root.
 */
internal fun Route.depth(): Int =
    when (this) {
        Route.Home -> 0

        is Route.Detail, Route.AddScan, is Route.OfferConsent, is Route.PresentScan,
        is Route.Present, Route.Settings,
        -> 1
    }

/**
 * Parent route to pop to on system back / edge-swipe. Returns `null` only for [Route.Home]
 * so the platform handles back there (i.e. moves the activity to background). Settings is
 * a pushed screen now that the tab bar is gone, so back returns to the deck.
 */
internal fun Route.parent(): Route? =
    when (this) {
        Route.Home -> null
        is Route.Detail -> Route.Home
        Route.AddScan -> Route.Home
        is Route.OfferConsent -> Route.Home
        is Route.PresentScan -> Route.Home
        is Route.Present -> Route.Home
        Route.Settings -> Route.Home
    }

/**
 * Whether the app-lock screen should cover the content.
 *
 * [requireAppUnlock] is the host activity's opt-out. `DcPresentationActivity` passes false:
 * a DC API presentation discloses nothing without a per-credential `BIOMETRIC_STRONG` +
 * `CryptoObject` prompt, which is strictly stronger than the lock screen's
 * `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`, so the lock is pure friction there. Every other
 * host — including `DcIssuanceActivity`, where the lock may be the only gate — keeps it.
 */
internal fun shouldShowAppLock(
    locked: Boolean,
    requireAppUnlock: Boolean,
): Boolean = locked && requireAppUnlock

/**
 * Where system back should navigate, or `null` when it must not navigate at all.
 *
 * [hostedByDcApi] is load bearing for security, not just ergonomics: [Route.Present]'s
 * parent is [Route.Home], so without this a back gesture inside the PendingIntent-launched
 * DC API activity would render the full credential deck — and with the app lock skipped
 * (see [shouldShowAppLock]) that deck would be unauthenticated. In DC API mode the host
 * cancels the platform request instead.
 */
internal fun backTargetFor(
    current: Route,
    showingAppLock: Boolean,
    hostedByDcApi: Boolean,
): Route? =
    when {
        showingAppLock -> null
        hostedByDcApi -> null
        else -> current.parent()
    }

/** Wraps an inbound deep link / DC API entry until the UI is ready to consume it. */
sealed interface DeepLink {
    data class CredentialOffer(
        val uri: Uri,
    ) : DeepLink

    data class PresentationRequest(
        val uri: Uri,
    ) : DeepLink
}

/** Funnels deep links from `MainActivity` to the composition. */
class DeepLinkRouter {
    private val channel = Channel<DeepLink>(BUFFERED)
    val events = channel.receiveAsFlow()

    suspend fun emit(link: DeepLink) = channel.send(link)
}
