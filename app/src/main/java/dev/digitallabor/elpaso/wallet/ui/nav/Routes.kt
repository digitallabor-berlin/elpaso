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
