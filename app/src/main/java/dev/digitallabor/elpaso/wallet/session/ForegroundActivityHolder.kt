package dev.digitallabor.elpaso.wallet.session

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * Tracks the currently-resumed [FragmentActivity] so non-UI components (issuance signer,
 * background coroutines) can reach a host for `BiometricPrompt` without smuggling Activity
 * references through the DI graph. Register the instance via
 * `Application.registerActivityLifecycleCallbacks(...)`.
 */
class ForegroundActivityHolder : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed: FragmentActivity? = null

    val current: FragmentActivity? get() = resumed

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {
        if (activity is FragmentActivity) resumed = activity
    }
    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
    }
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (resumed === activity) resumed = null
    }
}
