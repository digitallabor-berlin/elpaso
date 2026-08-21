package dev.digitallabor.elpaso.wallet.session

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * Tracks whether the wallet content is currently accessible. Initial state is [LockState.Locked]
 * so a cold start always prompts. Registered as a [androidx.lifecycle.ProcessLifecycleOwner]
 * observer so backgrounding/foregrounding the whole app drives re-lock with a short grace
 * window to tolerate quick switches (notifications, copying a 2FA code, etc.).
 */
class AppLockManager : DefaultLifecycleObserver {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var backgroundedAtMs: Long = 0L

    fun unlock() { _state.value = LockState.Unlocked }

    fun lock() { _state.value = LockState.Locked }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMs = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (_state.value == LockState.Unlocked &&
            SystemClock.elapsedRealtime() - backgroundedAtMs >= GRACE_MS
        ) {
            lock()
        }
    }

    private companion object {
        const val GRACE_MS = 30_000L
    }
}
