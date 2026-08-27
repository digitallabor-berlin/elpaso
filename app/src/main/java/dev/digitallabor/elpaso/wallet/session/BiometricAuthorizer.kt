package dev.digitallabor.elpaso.wallet.session

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import java.security.Signature

/**
 * Wraps BiometricPrompt to drive `setUserAuthenticationRequired(true)` device keys.
 *
 * Callers supply the device key alias of the credential they want to use; we produce an
 * authorised [Signature] the presentation builder can sign with.
 *
 * Two key shapes exist and the difference is user-visible:
 *
 * - **Time-bound** (`KeyManager` provisions new keys this way): any `BIOMETRIC_STRONG`
 *   auth within `KeyManager.AUTH_VALIDITY_SECONDS` authorises the key, so one prompt can
 *   cover several credentials. `Signature.initSign` throws until that auth has happened,
 *   so the prompt runs without a CryptoObject and the Signature is built afterwards.
 * - **Per-use** (credentials issued before the switch; keystore auth parameters are
 *   immutable, so these never migrate): keystore authorises exactly one `Signature`,
 *   supplied through a `CryptoObject`. N such credentials cost N prompts, unavoidably.
 */
class BiometricAuthorizer(
    private val context: Context,
    private val keyManager: KeyManager,
) {
    /**
     * Authorise every alias in [deviceKeyAliases] with as few prompts as keystore allows,
     * then hand back one [Signature] per alias.
     *
     * This is the entry point for a `credential_sets` presentation, where several
     * credentials are disclosed together. See [planBiometricAuth] for the prompt
     * arithmetic; the short version is one shared prompt for all time-bound keys plus one
     * per legacy per-use key.
     *
     * [onAuthorized] receives a map keyed by alias — callers must not assume ordering or
     * that a single Signature covers everything.
     */
    fun authorizeAll(
        activity: FragmentActivity,
        deviceKeyAliases: List<String>,
        onAuthorized: (Map<String, Signature>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val plan = planBiometricAuth(deviceKeyAliases) { keyManager.usesTimeBoundAuth(it) }
        Log.i(
            LOG_TAG,
            "authorizeAll aliases=${deviceKeyAliases.size} shared=${plan.sharedAliases.size} " +
                "per_use=${plan.perUseAliases.size} prompts=${plan.promptCount}",
        )
        val collected = linkedMapOf<String, Signature>()

        // Per-use keys cannot be batched, so they are walked one prompt at a time. Written
        // as a recursive continuation because BiometricPrompt is callback-based.
        fun authorizeRemainingPerUse(remaining: List<String>) {
            val next = remaining.firstOrNull()
            if (next == null) {
                onAuthorized(collected)
                return
            }
            authorize(
                activity = activity,
                deviceKeyAlias = next,
                onAuthorized = { signature ->
                    collected[next] = signature
                    authorizeRemainingPerUse(remaining.drop(1))
                },
                onError = onError,
            )
        }

        if (plan.sharedAliases.isEmpty()) {
            authorizeRemainingPerUse(plan.perUseAliases)
            return
        }

        promptForSession(
            activity = activity,
            onAuthenticated = {
                var failure: String? = null
                for (alias in plan.sharedAliases) {
                    val signature = runCatching { keyManager.signatureForAuth(alias) }.getOrNull()
                    if (signature == null) {
                        failure = "Authenticated but failed to initialise Signature for $alias"
                        break
                    }
                    collected[alias] = signature
                }
                if (failure != null) onError(failure) else authorizeRemainingPerUse(plan.perUseAliases)
            },
            onError = onError,
        )
    }

    /** Authorise a single alias. Prefer [authorizeAll] when signing with more than one. */
    fun authorize(
        activity: FragmentActivity,
        deviceKeyAlias: String,
        onAuthorized: (Signature) -> Unit,
        onError: (String) -> Unit,
    ) {
        val signatureForCryptoObject =
            runCatching { keyManager.signatureForAuth(deviceKeyAlias) }
                .getOrElse { error ->
                    if (error is UserNotAuthenticatedException) {
                        Log.i(LOG_TAG, "initSign required prior auth — using CryptoObject-less prompt")
                        null
                    } else {
                        onError(error.message ?: "Failed to load key")
                        return
                    }
                }
        val prompt =
            buildPrompt(
                activity = activity,
                onSucceeded = { result ->
                    val signature =
                        result.cryptoObject?.signature
                            ?: runCatching { keyManager.signatureForAuth(deviceKeyAlias) }.getOrNull()
                    if (signature != null) {
                        onAuthorized(signature)
                    } else {
                        onError("Authenticated but failed to initialise Signature")
                    }
                },
                onError = onError,
            )
        if (signatureForCryptoObject != null) {
            prompt.authenticate(promptInfo(), BiometricPrompt.CryptoObject(signatureForCryptoObject))
        } else {
            prompt.authenticate(promptInfo())
        }
    }

    /**
     * One `BIOMETRIC_STRONG` authentication with no CryptoObject attached, which is what
     * unlocks every time-bound key on the device for `KeyManager.AUTH_VALIDITY_SECONDS`.
     */
    private fun promptForSession(
        activity: FragmentActivity,
        onAuthenticated: () -> Unit,
        onError: (String) -> Unit,
    ) {
        buildPrompt(
            activity = activity,
            onSucceeded = { onAuthenticated() },
            onError = onError,
        ).authenticate(promptInfo())
    }

    private fun buildPrompt(
        activity: FragmentActivity,
        onSucceeded: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (String) -> Unit,
    ): BiometricPrompt =
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSucceeded(result)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    Log.w(LOG_TAG, "auth error code=$errorCode msg=$errString")
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // Silent — user can retry.
                }
            },
        )

    private fun promptInfo(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(context.getString(R.string.biometric_prompt_title))
            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
            .setNegativeButtonText(context.getString(R.string.biometric_prompt_negative))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

    private companion object {
        const val LOG_TAG = "BiometricAuthorizer"
    }
}
