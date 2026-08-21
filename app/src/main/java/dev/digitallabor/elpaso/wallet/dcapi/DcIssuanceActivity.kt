package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.credentials.CreateDigitalCredentialResponse
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import dev.digitallabor.elpaso.wallet.ui.WalletAppRoot
import dev.digitallabor.elpaso.wallet.ui.nav.Route
import dev.digitallabor.elpaso.wallet.ui.theme.ElPasoTheme
import org.koin.android.ext.android.get

/**
 * Entry point when the user picks "Save to El Paso" from the system credential-creation
 * sheet, i.e. a `navigator.credentials.create()` call carrying an OpenID4VCI credential
 * offer.
 *
 * The offer is mapped to an `openid-credential-offer://` URI and handed to the ordinary
 * consent flow at [Route.OfferConsent], so issuance is performed by the same
 * [IssuanceClient] pipeline the deep-link path uses.
 *
 * Entry goes through [WalletAppRoot] rather than `AddOfferFlow` directly on purpose:
 * `WalletAppRoot` owns `AppLockManager` and shows the lock screen when the wallet is locked.
 * Hosting the consent screen directly would let a DC API caller drive issuance past the app
 * lock.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class DcIssuanceActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(LOG_TAG, "onCreate action=${intent?.action} has_extras=${intent?.extras != null}")

        val request =
            runCatching {
                PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            }.getOrNull()
        if (request == null) {
            Log.w(LOG_TAG, "No ProviderCreateCredentialRequest in intent; aborting")
            finishWithException("Missing create credential request")
            return
        }

        val requestJson =
            runCatching {
                request.callingRequest.credentialData.getString(BUNDLE_KEY_REQUEST_JSON)
            }.getOrNull()
        if (requestJson.isNullOrEmpty()) {
            Log.w(LOG_TAG, "Create request carried no request JSON; aborting")
            finishWithException("Missing request JSON")
            return
        }

        Log.i(
            LOG_TAG,
            "create request calling_package=${request.callingAppInfo.packageName} " +
                "json_len=${requestJson.length}",
        )

        when (val mapped = DcIssuanceRequest.map(requestJson)) {
            is DcIssuanceRequest.Result.Rejected -> {
                Log.w(LOG_TAG, "rejected: ${mapped.reason}")
                finishWithException(mapped.reason)
            }

            is DcIssuanceRequest.Result.Offer -> {
                Log.i(LOG_TAG, "accepted issuer=${mapped.issuerId}")
                // IssuanceClient is a singleton holding one session. Concurrent in-app and
                // DC API issuance is unsupported; clear any stale state so this flow starts
                // from Idle rather than inheriting an abandoned one.
                runCatching { get<IssuanceClient>().reset() }
                setContent {
                    ElPasoTheme {
                        WalletAppRoot(
                            startRoute = Route.OfferConsent(mapped.offerUri),
                            onDcApiIssuanceDone = { finishWithSuccess() },
                            onDcApiCancel = { finishWithCancellation() },
                        )
                    }
                }
            }
        }
    }

    private fun finishWithSuccess() {
        Log.i(LOG_TAG, "finishWithSuccess")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            data,
            CreateDigitalCredentialResponse(DcIssuanceRequest.ACK_RESPONSE_JSON),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithException(message: String) {
        Log.w(LOG_TAG, "finishWithException: $message")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialException(
            data,
            CreateCredentialUnknownException(message),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithCancellation() {
        Log.i(LOG_TAG, "finishWithCancellation")
        val data = Intent()
        PendingIntentHandler.setCreateCredentialException(
            data,
            CreateCredentialCancellationException(),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private companion object {
        const val LOG_TAG = "DcIssuanceActivity"

        /** Bundle key Credential Manager uses to carry the request JSON. */
        const val BUNDLE_KEY_REQUEST_JSON = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
    }
}
