package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.registry.provider.selectedCredentialSet
import androidx.credentials.registry.provider.selectedEntryId
import androidx.fragment.app.FragmentActivity
import dev.digitallabor.elpaso.wallet.ui.WalletAppRoot
import dev.digitallabor.elpaso.wallet.ui.nav.Route
import dev.digitallabor.elpaso.wallet.ui.theme.ElPasoTheme
import java.security.MessageDigest

/**
 * Entry point when the user picks a wallet credential from the system DC API selector.
 *
 * Decodes the OpenID4VP request from the intent, identifies the user's selection, then
 * hands off to the standard `PresentScreen` consent flow. After authorisation the screen
 * calls back here to seal the platform response.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class DcPresentationActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.i(LOG_TAG, "onCreate action=${intent?.action} has_extras=${intent?.extras != null}")

        val getRequest =
            runCatching {
                PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            }.getOrNull()

        if (getRequest == null) {
            Log.w(LOG_TAG, "No ProviderGetCredentialRequest in intent; aborting")
            finishWithException("Missing credential request")
            return
        }

        val rawJson =
            getRequest.credentialOptions
                .filterIsInstance<GetDigitalCredentialOption>()
                .firstOrNull()
                ?.requestJson
                .orEmpty()
        val selection = resolveSelection(getRequest)
        val callingAppOrigin = computeCallingAppOrigin(getRequest)
        // Pre-auth type tells us whether Credential Manager's prompt was biometric vs
        // device-credential. With the device key configured as time-bound BIOMETRIC_STRONG
        // (see KeyManager.AUTH_VALIDITY_SECONDS), a successful biometric pre-auth lets
        // BiometricAuthorizer skip its own prompt. A device-credential pre-auth doesn't
        // unlock the key, so a second prompt is unavoidable for those users.
        val preAuthType =
            runCatching {
                getRequest.biometricPromptResult?.authenticationResult?.authenticationType
            }.getOrNull()
        // `BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC == 2`. Anything else
        // (null / TYPE_DEVICE_CREDENTIAL = 1) means we still need our own biometric
        // prompt to provide the user-gesture-with-consent the platform expects before
        // it forwards our response to the verifier.
        val systemPreAuthBiometric = preAuthType == 2
        Log.i(
            LOG_TAG,
            "request received options=${getRequest.credentialOptions.size} " +
                "digital_options=${getRequest.credentialOptions.count { it is GetDigitalCredentialOption }} " +
                "request_json_len=${rawJson.length} " +
                "selected_set_id=${selection?.setId} " +
                "selected_credential_ids=${selection?.credentialIds} " +
                "selected_pins=${selection?.assignmentPins} " +
                "calling_package=${getRequest.callingAppInfo.packageName} origin=$callingAppOrigin " +
                "pre_auth_type=$preAuthType system_pre_auth_biometric=$systemPreAuthBiometric",
        )

        setContent {
            ElPasoTheme {
                WalletAppRoot(
                    startRoute =
                        Route.Present(
                            rawRequestJson = rawJson,
                            callingPackage = callingAppOrigin,
                            dcApiSelection = selection,
                            systemPreAuthBiometric = systemPreAuthBiometric,
                        ),
                    onDcApiResult = { responseJson -> finishWithSuccess(responseJson) },
                    onDcApiCancel = { finishWithCancellation() },
                    onDcApiError = { message -> finishWithException(message) },
                )
            }
        }
    }

    private fun finishWithSuccess(responseJson: String) {
        Log.i(LOG_TAG, "finishWithSuccess response_len=${responseJson.length}")
        val data = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            data,
            GetCredentialResponse(DigitalCredential(responseJson)),
        )
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithException(message: String) {
        Log.w(LOG_TAG, "finishWithException: $message")
        val data = Intent()
        PendingIntentHandler.setGetCredentialException(data, GetCredentialUnknownException(message))
        setResult(RESULT_OK, data)
        finish()
    }

    private fun finishWithCancellation() {
        Log.i(LOG_TAG, "finishWithCancellation")
        val data = Intent()
        PendingIntentHandler.setGetCredentialException(data, GetCredentialCancellationException())
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * Read the user's selection out of the platform's response.
     *
     * Our matcher reports every match through `AddEntrySet` / `AddEntryToSet`
     * (`matcher/upstream/openid4vp1_0.c:135`), which the host takes for any
     * `wasm_version > 1` — i.e. always, in practice. Credential Manager answers that with
     * the `CREDENTIAL_SET_*` extras behind [selectedCredentialSet], and leaves the
     * single-entry `CREDENTIAL_ID` extra behind [selectedEntryId] unset. Reading only the
     * latter therefore returned null on every request, discarded the user's choice, and
     * made the wallet re-ask for consent the platform had already collected.
     *
     * [selectedEntryId] is kept as a fallback for the legacy `AddStringIdEntry` path, so a
     * matcher rebuilt against an older host still works.
     */
    private fun resolveSelection(request: androidx.credentials.provider.ProviderGetCredentialRequest): DcApiSelection? {
        val set = runCatching { request.selectedCredentialSet }.getOrNull()
        if (set != null) {
            return DcApiSelection.fromEntrySet(
                setId = set.credentialSetId,
                credentials = set.credentials.map { it.credentialId to it.metadata },
            )
        }
        Log.w(LOG_TAG, "no selectedCredentialSet in request; falling back to selectedEntryId")
        return DcApiSelection.ofSingleEntry(runCatching { request.selectedEntryId }.getOrNull())
    }

    /**
     * Resolve the caller's origin per the OpenID4VP DC API profile.
     *
     * - Privileged browser callers (Chrome, etc.) are allowed to claim the web origin of
     *   the page that initiated `navigator.credentials.get({digital:…})`. We get that via
     *   `CallingAppInfo.getOrigin(privilegedAppsJson)`, which only returns non-null when
     *   the caller's signing cert matches an entry in [PRIVILEGED_APPS_JSON].
     * - Native app callers (no entry) get `android:apk-key-hash:<sha256>` — computed
     *   directly from the signing cert.
     *
     * Either form gets prefixed with `origin:` in `PresentationClient.resolveDcApi` to
     * produce the KB-JWT `aud` value required by OpenID4VP 1.0 §B.3.4.
     */
    private fun computeCallingAppOrigin(request: androidx.credentials.provider.ProviderGetCredentialRequest): String? {
        val privilegedOrigin =
            runCatching {
                request.callingAppInfo.getOrigin(PRIVILEGED_APPS_JSON)
            }.getOrNull()
        if (!privilegedOrigin.isNullOrEmpty()) return privilegedOrigin
        return runCatching {
            val signingInfo = request.callingAppInfo.signingInfoCompat
            val cert =
                signingInfo.signingCertificateHistory.firstOrNull()?.toByteArray()
                    ?: return@runCatching null
            val sha = MessageDigest.getInstance("SHA-256").digest(cert)
            val b64 = Base64.encodeToString(sha, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            "android:apk-key-hash:$b64"
        }.getOrNull()
    }

    private companion object {
        const val LOG_TAG = "DcPresentationActivity"

        /**
         * Allow-list of apps trusted to assert origins on behalf of others. Currently
         * Chrome Stable/Beta (Google Play release key). Extend this with additional
         * browser signing certs (Edge, Brave, Firefox) as you validate them.
         *
         * Format is what `CallingAppInfo.getOrigin(...)` expects in androidx.credentials
         * 1.6+: a JSON document with an `apps` array, each entry pinning a package name
         * to one or more signing-cert SHA-256 fingerprints.
         */
        const val PRIVILEGED_APPS_JSON = """
        {
          "apps": [
            {
              "type": "android",
              "info": {
                "package_name": "com.android.chrome",
                "signatures": [
                  {
                    "build": "release",
                    "cert_fingerprint_sha256": "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"
                  }
                ]
              }
            }
          ]
        }
        """
    }
}
