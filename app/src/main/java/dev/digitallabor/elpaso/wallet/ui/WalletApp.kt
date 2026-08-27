package dev.digitallabor.elpaso.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.settings.ThemePreference
import dev.digitallabor.elpaso.wallet.session.AppLockManager
import dev.digitallabor.elpaso.wallet.session.LockState
import dev.digitallabor.elpaso.wallet.ui.add.AddOfferFlow
import dev.digitallabor.elpaso.wallet.ui.detail.PassDetailScreen
import dev.digitallabor.elpaso.wallet.ui.home.HomeScreen
import dev.digitallabor.elpaso.wallet.ui.lock.LockScreen
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLink
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLinkRouter
import dev.digitallabor.elpaso.wallet.ui.nav.Route
import dev.digitallabor.elpaso.wallet.ui.nav.backTargetFor
import dev.digitallabor.elpaso.wallet.ui.nav.depth
import dev.digitallabor.elpaso.wallet.ui.nav.shouldShowAppLock
import dev.digitallabor.elpaso.wallet.ui.present.PresentScanScreen
import dev.digitallabor.elpaso.wallet.ui.present.PresentScreen
import dev.digitallabor.elpaso.wallet.ui.settings.SettingsScreen
import dev.digitallabor.elpaso.wallet.ui.theme.ElPasoTheme
import org.koin.compose.koinInject

@Composable
fun WalletApp() {
    val settings: SettingsRepository = koinInject()
    val themePref by settings.themePreference.collectAsState(initial = ThemePreference.System)
    val darkTheme =
        when (themePref) {
            ThemePreference.Light -> false
            ThemePreference.Dark -> true
            ThemePreference.System -> isSystemInDarkTheme()
        }
    ElPasoTheme(darkTheme = darkTheme) {
        WalletAppRoot(startRoute = Route.Home)
    }
}

@Composable
fun WalletAppRoot(
    startRoute: Route,
    /**
     * Whether the app-lock screen gates this host's content. See
     * [dev.digitallabor.elpaso.wallet.ui.nav.shouldShowAppLock] for why
     * `DcPresentationActivity` — and only it — passes false.
     */
    requireAppUnlock: Boolean = true,
    onDcApiResult: ((responseJson: String) -> Unit)? = null,
    onDcApiCancel: (() -> Unit)? = null,
    onDcApiError: ((message: String) -> Unit)? = null,
    // Issuance returns a fixed acknowledgement rather than a computed response document,
    // so it gets its own no-argument callback instead of reusing onDcApiResult. Keeping the
    // ack in DcIssuanceActivity avoids a ui/ -> dcapi/ import.
    onDcApiIssuanceDone: (() -> Unit)? = null,
) {
    var current by remember { mutableStateOf(startRoute) }
    val router: DeepLinkRouter = koinInject()
    val appLock: AppLockManager = koinInject()
    val lockState = appLock.state.collectAsState()
    val locked =
        shouldShowAppLock(
            locked = lockState.value is LockState.Locked,
            requireAppUnlock = requireAppUnlock,
        )
    // Any DC API host supplies a cancel callback; that is what marks this composition as
    // running on behalf of the platform rather than inside the wallet's own task.
    val hostedByDcApi = onDcApiCancel != null

    LaunchedEffect(router.events) {
        router.events.collect { v ->
            when (v) {
                is DeepLink.CredentialOffer -> current = Route.OfferConsent(v.uri.toString())
                is DeepLink.PresentationRequest -> current = Route.Present(v.uri.toString(), null, null)
            }
        }
    }

    // System back / edge-swipe gesture. Every route except Home pops to its parent;
    // Home and the lock screen let the platform handle it (i.e. the activity moves to
    // background).
    val backTarget =
        backTargetFor(
            current = current,
            showingAppLock = locked,
            hostedByDcApi = hostedByDcApi,
        )
    // In DC API mode back must answer the platform (a cancellation) instead of popping to
    // Home inside an activity the system launched for a single screen.
    BackHandler(enabled = backTarget != null || (hostedByDcApi && !locked)) {
        when {
            backTarget != null -> current = backTarget
            hostedByDcApi && !locked -> onDcApiCancel?.invoke()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { inner ->
        // ElPasoTheme is already applied at the top of WalletApp(); no need to wrap again.
        if (locked) {
            LockScreen(modifier = Modifier.padding(inner))
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val forward = targetState.depth() > initialState.depth()
                    val backward = targetState.depth() < initialState.depth()
                    val spec = tween<Float>(durationMillis = 150)
                    val intSpec = tween<IntOffset>(durationMillis = 150)
                    when {
                        forward -> {
                            (slideInHorizontally(intSpec) { it } + fadeIn(spec)) togetherWith
                                (slideOutHorizontally(intSpec) { -it / 4 } + fadeOut(spec))
                        }

                        backward -> {
                            (slideInHorizontally(intSpec) { -it / 4 } + fadeIn(spec)) togetherWith
                                (slideOutHorizontally(intSpec) { it } + fadeOut(spec))
                        }

                        else -> {
                            fadeIn() togetherWith fadeOut()
                        }
                    }
                },
                label = "route-transition",
            ) { r ->
                when (r) {
                    Route.Home -> {
                        HomeScreen(
                            modifier = Modifier.padding(inner),
                            onAdd = { current = Route.AddScan },
                            onOpenCredential = { id -> current = Route.Detail(id) },
                            onPresentCredential = { id -> current = Route.PresentScan(id) },
                        )
                    }

                    is Route.Detail -> {
                        PassDetailScreen(
                            modifier = Modifier.padding(inner),
                            credentialId = r.credentialId,
                            onBack = { current = Route.Home },
                            onUse = { current = Route.AddScan },
                        )
                    }

                    Route.AddScan -> {
                        AddOfferFlow(
                            modifier = Modifier.padding(inner),
                            incomingOfferUri = null,
                            onDone = { current = Route.Home },
                            onCancel = { current = Route.Home },
                        )
                    }

                    is Route.OfferConsent -> {
                        AddOfferFlow(
                            modifier = Modifier.padding(inner),
                            incomingOfferUri = r.offerUri,
                            onDone = {
                                if (onDcApiIssuanceDone != null) {
                                    onDcApiIssuanceDone()
                                } else {
                                    current = Route.Home
                                }
                            },
                            onCancel = {
                                if (onDcApiCancel != null) onDcApiCancel() else current = Route.Home
                            },
                        )
                    }

                    is Route.PresentScan -> {
                        PresentScanScreen(
                            modifier = Modifier.padding(inner),
                            onResolved = { uri ->
                                current =
                                    Route.Present(
                                        rawRequestJson = uri,
                                        callingPackage = null,
                                        preselectedCredentialId = r.preselectedCredentialId,
                                    )
                            },
                            onCancel = { current = Route.Home },
                        )
                    }

                    is Route.Present -> {
                        PresentScreen(
                            modifier = Modifier.padding(inner),
                            route = r,
                            onDone = { current = Route.Home },
                            onCancel = {
                                if (onDcApiCancel != null) onDcApiCancel() else current = Route.Home
                            },
                            onDcApiResult = onDcApiResult,
                            onDcApiError = onDcApiError,
                        )
                    }

                    Route.Settings -> {
                        SettingsScreen(
                            modifier = Modifier.padding(inner),
                        )
                    }
                }
            }

            if (!locked && current is Route.Home) {
                SettingsButton(
                    onOpenSettings = { current = Route.Settings },
                    modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopRight).padding(inner),
                )
            }
        }
    }
}

/**
 * The wallet's only chrome: a floating settings affordance on the Passes screen. Keeps the
 * 56dp / 28dp-radius / 6dp-shadow language of the nav menu it replaced, so the edge-to-edge
 * full-bleed card deck is still the only thing competing for attention.
 */
@Composable
private fun SettingsButton(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.padding(top = 16.dp, end = 16.dp).size(56.dp),
        shadowElevation = 6.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable { onOpenSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.home_settings_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
