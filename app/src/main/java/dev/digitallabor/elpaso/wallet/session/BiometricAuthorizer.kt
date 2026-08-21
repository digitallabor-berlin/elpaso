package dev.digitallabor.elpaso.wallet.session

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import java.security.Signature

/**
 * Wraps BiometricPrompt to drive `setUserAuthenticationRequired(true)` device keys.
 *
 * Callers supply the [deviceKeyAlias] of the credential they want to use; we create an
 * initialised [Signature] via [KeyManager.signatureForAuth], wrap it in a CryptoObject, and
 * once the prompt succeeds we hand the now-authorised Signature back so the presentation
 * builder can sign with it.
 */
class BiometricAuthorizer(
    private val context: Context,
    private val keyManager: KeyManager,
) {
    /**
     * @param skipPromptIfUnlocked when true, the caller asserts that the system (e.g.
     *   Credential Manager / Play Services for a DC API request) has just performed a
     *   `BIOMETRIC_STRONG` authentication inline with this transaction. Only then is it
     *   safe to skip our own `BiometricPrompt` — both because the key is unlocked *and*
     *   because the platform has captured the user-gesture-with-consent that downstream
     *   surfaces (Chrome's DC API, the verifier's page) require. When false (deep-link
     *   path, or DC API without system pre-auth), we always prompt.
     */
    fun authorize(
        activity: FragmentActivity,
        deviceKeyAlias: String,
        @Suppress("UNUSED_PARAMETER") skipPromptIfUnlocked: Boolean = false,
        onAuthorized: (Signature) -> Unit,
        onError: (String) -> Unit,
    ) {
        // We support two key shapes:
        //   (a) Per-use auth (current default — `KeyManager` provisions new keys this
        //       way). `Signature.initSign(privateKey)` succeeds without recent biometric;
        //       we wrap the Signature in a CryptoObject and the prompt unlocks it.
        //   (b) Time-bound auth (left over from credentials provisioned with the earlier
        //       30s-validity spec). For these `initSign` itself throws
        //       UserNotAuthenticatedException when there's no recent auth, so we can't
        //       build a CryptoObject up-front — we run a CryptoObject-less prompt and
        //       initialise the Signature *after* the user has authenticated.
        val executor = ContextCompat.getMainExecutor(context)
        val signatureForCryptoObject = runCatching { keyManager.signatureForAuth(deviceKeyAlias) }
            .getOrElse { error ->
                if (error is UserNotAuthenticatedException) {
                    Log.i(LOG_TAG, "initSign required prior auth — using CryptoObject-less prompt")
                    null
                } else {
                    onError(error.message ?: "Failed to load key")
                    return
                }
            }
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val sig = result.cryptoObject?.signature
                        ?: runCatching { keyManager.signatureForAuth(deviceKeyAlias) }.getOrNull()
                    if (sig != null) onAuthorized(sig)
                    else onError("Authenticated but failed to initialise Signature")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.w(LOG_TAG, "auth error code=$errorCode msg=$errString")
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Silent — user can retry.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.biometric_prompt_negative))
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        if (signatureForCryptoObject != null) {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signatureForCryptoObject))
        } else {
            prompt.authenticate(info)
        }
    }

    private companion object {
        const val LOG_TAG = "BiometricAuthorizer"
    }
}
