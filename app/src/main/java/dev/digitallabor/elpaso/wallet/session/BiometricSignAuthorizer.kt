package dev.digitallabor.elpaso.wallet.session

import java.security.Signature
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Suspending bridge around [BiometricAuthorizer] for non-UI components that need a
 * `BiometricPrompt`-authorised [Signature]. Fires the prompt on the main thread using whichever
 * [androidx.fragment.app.FragmentActivity] is currently in the foreground.
 */
class BiometricSignAuthorizer(
    private val activities: ForegroundActivityHolder,
    private val biometric: BiometricAuthorizer,
) {

    suspend fun authorize(deviceKeyAlias: String): Signature = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val activity = activities.current
            if (activity == null) {
                cont.resumeWithException(
                    IllegalStateException("No foreground activity available for BiometricPrompt"),
                )
                return@suspendCancellableCoroutine
            }
            biometric.authorize(
                activity = activity,
                deviceKeyAlias = deviceKeyAlias,
                onAuthorized = { sig -> if (cont.isActive) cont.resume(sig) },
                onError = { msg ->
                    if (cont.isActive) cont.resumeWithException(SecurityException(msg))
                },
            )
        }
    }
}
