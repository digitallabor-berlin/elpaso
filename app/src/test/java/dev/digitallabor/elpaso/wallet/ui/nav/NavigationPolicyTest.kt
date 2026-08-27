package dev.digitallabor.elpaso.wallet.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two policies that decide whether `WalletAppRoot` shows the app-lock screen and
 * where system back navigates.
 *
 * These matter together, not separately. The DC API presentation activity skips the app
 * lock because every disclosure there is already gated by a per-credential
 * `BIOMETRIC_STRONG` + `CryptoObject` prompt — strictly stronger than the lock screen's
 * `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`. But `Route.Present.parent()` is `Route.Home`, so
 * dropping the lock without also stopping back from popping would expose the whole
 * credential deck inside a PendingIntent-launched activity with no authentication at all.
 * The leak guard below is the test that keeps those two facts wired together.
 */
class NavigationPolicyTest {
    private fun presentRoute() =
        Route.Present(
            rawRequestJson = "{}",
            callingPackage = "https://verifier.example",
        )

    @Test
    fun `app lock shows when locked and unlock is required`() {
        assertTrue(shouldShowAppLock(locked = true, requireAppUnlock = true))
    }

    @Test
    fun `app lock is skipped when the host opts out`() {
        assertFalse(shouldShowAppLock(locked = true, requireAppUnlock = false))
    }

    @Test
    fun `app lock never shows once unlocked`() {
        assertFalse(shouldShowAppLock(locked = false, requireAppUnlock = true))
        assertFalse(shouldShowAppLock(locked = false, requireAppUnlock = false))
    }

    @Test
    fun `back from a DC API hosted presentation must not pop to home`() {
        // The leak guard. Popping here would render the credential deck inside the
        // DC API activity, which — with the app lock skipped — is unauthenticated.
        assertNull(
            backTargetFor(
                current = presentRoute(),
                showingAppLock = false,
                hostedByDcApi = true,
            ),
        )
    }

    @Test
    fun `back from an in-app presentation pops to home`() {
        assertEquals(
            Route.Home,
            backTargetFor(
                current = presentRoute(),
                showingAppLock = false,
                hostedByDcApi = false,
            ),
        )
    }

    @Test
    fun `back is inert while the app lock is showing`() {
        assertNull(
            backTargetFor(
                current = presentRoute(),
                showingAppLock = true,
                hostedByDcApi = false,
            ),
        )
    }

    @Test
    fun `back at home defers to the platform`() {
        assertNull(
            backTargetFor(current = Route.Home, showingAppLock = false, hostedByDcApi = false),
        )
    }

    @Test
    fun `every non-home route pops to home in the normal app`() {
        val routes =
            listOf(
                Route.Detail("cred-1"),
                Route.AddScan,
                Route.OfferConsent("openid-credential-offer://x"),
                Route.PresentScan("cred-1"),
                presentRoute(),
                Route.Settings,
            )
        routes.forEach { route ->
            assertEquals(
                "expected $route to pop to Home",
                Route.Home,
                backTargetFor(current = route, showingAppLock = false, hostedByDcApi = false),
            )
        }
    }

    @Test
    fun `home is the only zero-depth route`() {
        assertEquals(0, Route.Home.depth())
        assertEquals(1, presentRoute().depth())
        assertEquals(1, Route.Settings.depth())
    }
}
