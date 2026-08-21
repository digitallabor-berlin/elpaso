package dev.digitallabor.elpaso.wallet.session

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.digitallabor.elpaso.wallet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspending BiometricPrompt bridge that unlocks an AES/GCM [Cipher].
 *
 * Mirrors [BiometricSignAuthorizer] but works with symmetric keys — required for the
 * banking PIN store, where the Keystore key is `setUserAuthenticationRequired(true)` and
 * decrypt/encrypt operations need a freshly-authenticated cipher.
 */
class BiometricCipherAuthorizer(
    private val context: Context,
    private val activities: ForegroundActivityHolder,
) {

    suspend fun authorize(cipher: Cipher, subtitle: String? = null): Cipher =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val activity = activities.current
                if (activity == null) {
                    cont.resumeWithException(
                        IllegalStateException("No foreground activity available for BiometricPrompt"),
                    )
                    return@suspendCancellableCoroutine
                }
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val unlocked = result.cryptoObject?.cipher
                            if (unlocked != null) cont.resume(unlocked)
                            else cont.resumeWithException(
                                SecurityException("Auth succeeded but cipher missing"),
                            )
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cont.resumeWithException(SecurityException(errString.toString()))
                        }
                        override fun onAuthenticationFailed() = Unit
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(context.getString(R.string.biometric_prompt_title))
                    .setSubtitle(subtitle ?: context.getString(R.string.biometric_prompt_subtitle))
                    .setNegativeButtonText(context.getString(R.string.biometric_prompt_negative))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            }
        }
}
