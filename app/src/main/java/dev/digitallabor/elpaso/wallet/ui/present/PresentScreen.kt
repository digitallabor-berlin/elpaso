package dev.digitallabor.elpaso.wallet.ui.present

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.dcapi.DcApiSelection
import dev.digitallabor.elpaso.wallet.domain.claims.ClaimLabelResolver
import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.PresentationClient
import dev.digitallabor.elpaso.wallet.presentation.txdata.DynamicTransactionDataBlock
import dev.digitallabor.elpaso.wallet.presentation.txdata.LabelText
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionDataBlock
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionMetadataResolver
import dev.digitallabor.elpaso.wallet.presentation.txdata.plainText
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.LocaleSelector
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderPlan
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderedLabel
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.TransactionDataCompatibilityChecker
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.ValidationResult
import dev.digitallabor.elpaso.wallet.session.BiometricAuthorizer
import dev.digitallabor.elpaso.wallet.ui.common.ErrorModal
import dev.digitallabor.elpaso.wallet.ui.nav.Route
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.koinInject
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentScreen(
    modifier: Modifier = Modifier,
    route: Route.Present,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onDcApiResult: ((responseJson: String) -> Unit)? = null,
    onDcApiError: ((message: String) -> Unit)? = null,
) {
    val client: PresentationClient = koinInject()
    val biometric: BiometricAuthorizer = koinInject()
    val repository: CredentialRepository = koinInject()
    val metadataResolver: TransactionMetadataResolver = koinInject()
    val compatibilityChecker: TransactionDataCompatibilityChecker = koinInject()
    val settings: SettingsRepository = koinInject()
    val state by client.state.collectAsState(initial = PresentationClient.State.Idle)
    val developerMode by settings.developerMode.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var credentialsById by remember { mutableStateOf<Map<String, Credential>>(emptyMap()) }
    // Issuer-supplied screen title from the verified credential metadata
    // (paso-proof-metadata.md §3.2 — "Title for the consent screen"). Replaces the
    // hardcoded app-bar title when present; falls back to it otherwise. Reset per
    // route so a new resolution doesn't inherit the prior screen's title.
    var dynamicScreenTitle by remember(route) { mutableStateOf<String?>(null) }
    // DC API mode = the activity was launched by the system DC API selector. We branch
    // dispatch to authorizeDcApi (response inline, no HTTP) and skip the credential picker
    // since the platform's WASM matcher already chose one entry.
    val isDcApi = onDcApiResult != null

    // Guard against a stale State.Done lingering on the singleton client from a previous
    // presentation. resolveDeepLink resets the state synchronously, but the very first
    // composition can still observe the leftover Done before that runs. Ignore Done until
    // we've seen at least one non-terminal state for the current route.
    var enteredFlow by remember(route) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is PresentationClient.State.Done) enteredFlow = true
    }

    // DC API + matcher-preselected credential = the user already confirmed merchant /
    // amount in the system selector (the matcher's payment entry). Skipping the wallet's
    // review screen avoids a redundant second tap. We still demand a biometric prompt
    // because PaSO requires `bio_strong` + `hwk` in the AMR claims. Auto-authorize only
    // when the verifier is trusted and exactly one match was preselected — otherwise we
    // fall back to the review UI so the user can resolve ambiguity / see the warning.
    var autoAuthorized by remember(route) { mutableStateOf(false) }

    LaunchedEffect(state) {
        val resolved = state as? PresentationClient.State.Resolved ?: return@LaunchedEffect
        val ids = resolved.matches.map { it.credentialId }.distinct()
        val loaded = ids.mapNotNull { id -> repository.byId(id)?.let { id to it } }.toMap()
        credentialsById = loaded
    }

    LaunchedEffect(route) {
        // Wipe any state left behind by a previous, cancelled presentation BEFORE
        // we trigger the new resolve. resolveDeepLink/resolveDcApi reset internally
        // (see PresentationClient.kt:235) but only after entering the suspend
        // function — for one frame the screen would otherwise read the stale
        // Resolved from the singleton and capture it in the click-handler
        // closures, leaving the bottom buttons inert until resolution finishes.
        client.state.value = PresentationClient.State.Idle
        if (route.rawRequestJson.startsWith("openid4vp://") ||
            route.rawRequestJson.startsWith("eudi-openid4vp://")
        ) {
            client.resolveDeepLink(android.net.Uri.parse(route.rawRequestJson))
        } else if (route.rawRequestJson.isNotBlank()) {
            client.resolveDcApi(
                rawRequestJson = route.rawRequestJson,
                callingAppOrigin = route.callingPackage,
                // The DC API selector's own choice wins. The single-id fallback covers the
                // legacy `AddStringIdEntry` matcher path, which carries no set metadata.
                selection =
                    route.dcApiSelection
                        ?: DcApiSelection.ofSingleEntry(route.preselectedCredentialId),
            )
        }
    }

    // When the user leaves the screen (X icon → onCancel → route change, or back
    // gesture via WalletApp's BackHandler), reset the singleton client state so
    // the next opening starts clean. Without this, navigating away from a
    // Resolved/Failed state leaves that value live on the client; the next
    // PresentScreen mount reads it during initial composition, ResolvedContent
    // renders with the prior session's data, and the click handlers capture a
    // stale `s` — making the Approve/Cancel buttons appear unresponsive.
    DisposableEffect(Unit) {
        onDispose {
            client.state.value = PresentationClient.State.Idle
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val titleRes =
        when (val s = state) {
            is PresentationClient.State.Resolved -> {
                if (paymentSummaryOf(s.transactionData) != null) {
                    R.string.present_title_payment
                } else {
                    R.string.present_title
                }
            }

            else -> {
                R.string.present_title
            }
        }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = dynamicScreenTitle ?: stringResource(titleRes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.present_cancel),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        Box(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val s = state) {
                PresentationClient.State.Idle, PresentationClient.State.Dispatching -> {
                    LoadingState(label = stringResource(R.string.present_loading))
                }

                is PresentationClient.State.Failed -> {
                    if (isDcApi && onDcApiError != null) {
                        LaunchedEffect(s) { onDcApiError(s.message ?: "Presentation failed") }
                    }
                    ErrorModal(
                        message = stringResource(R.string.present_failed_generic),
                        technicalDetails = s.message,
                        onDismissRequest = onCancel,
                    )
                }

                is PresentationClient.State.Done -> {
                    if (enteredFlow) LaunchedEffect(Unit) { onDone() }
                }

                is PresentationClient.State.Resolved -> {
                    // Suspending wrapper around the callback-based BiometricAuthorizer so we
                    // can prompt for each device key sequentially when the request requires
                    // multiple credentials (DCQL `credential_sets`).
                    suspend fun promptSignatures(matches: List<DcqlMatcher.Match>): Map<String, java.security.Signature> =
                        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                            biometric.authorizeAll(
                                activity = context as FragmentActivity,
                                deviceKeyAliases = matches.map { "cred_${it.credentialId}" },
                                onAuthorized = { signatures ->
                                    if (cont.isActive) cont.resumeWith(Result.success(signatures))
                                },
                                onError = { msg ->
                                    if (cont.isActive) cont.resumeWith(Result.failure(SecurityException(msg)))
                                },
                            )
                        }

                    val authorize: (List<DcqlMatcher.Match>) -> Unit = { matches ->
                        scope.launch {
                            try {
                                // One prompt covers every time-bound device key, so a
                                // multi-credential `credential_sets` response no longer
                                // costs one scan per credential. Legacy per-use keys still
                                // get their own prompt — see BiometricAuthorizer.
                                val signatures = promptSignatures(matches)
                                val pairs =
                                    matches.map { m ->
                                        m to
                                            (
                                                signatures["cred_${m.credentialId}"]
                                                    ?: throw SecurityException(
                                                        "No authorised signature for ${m.credentialId}",
                                                    )
                                            )
                                    }
                                if (isDcApi) {
                                    client
                                        .authorizeDcApi(s, pairs)
                                        .onSuccess { responseJson -> onDcApiResult?.invoke(responseJson) }
                                } else {
                                    client.authorize(s, pairs)
                                }
                            } catch (e: SecurityException) {
                                // Drive failures through the PresentationClient state machine
                                // so the Failed branch (and DC API onDcApiError plumbing) can
                                // unwedge the activity instead of stalling on the loading
                                // screen.
                                client.state.value =
                                    PresentationClient.State.Failed(
                                        e.message ?: "Authentication failed",
                                        e,
                                    )
                            }
                        }
                    }

                    // Auto-authorize: in DC API mode the user already went through the
                    // system credential selector (which renders merchant + amount via the
                    // matcher's payment entry). A second review tap in our UI is just
                    // friction. We skip it when:
                    //  - the verifier is trusted OR developer mode is on, AND
                    //  - there is exactly one candidate — nothing for the user to choose
                    //    between, so the review screen would be pure friction on top of
                    //    the system selector they already went through. More than one and
                    //    the user must pick which credential is disclosed.
                    //  - AND no entry carries ad-hoc metadata (paso-proof-metadata.md §5).
                    //
                    // That last condition is not a performance guard, it is the whole point
                    // of the ad-hoc channel. The justification for skipping our review screen
                    // is that the system selector already showed the user this transaction —
                    // but the DC API matcher (matcher/upstream/openid4vp1_0.c) never parses
                    // the `metadata` parameter, so what it rendered carries none of the
                    // issuer-signed labels, title or security hint the ad-hoc JWT supplies.
                    // Auto-authorizing here would approve a transaction whose issuer-signed
                    // description the user never saw, and would skip §5.3 verification
                    // entirely — a verifier could attach a forged `metadata` and never have
                    // it checked. Falling through to ResolvedContent both renders those
                    // labels and enforces the refusal.
                    val carriesAdhocMetadata =
                        remember(s) { s.transactionData.any { it.adhocMetadataJwt != null } }
                    val canAutoAuthorize =
                        isDcApi &&
                            (s.verifier.trusted || developerMode) &&
                            s.candidates.size == 1 &&
                            !carriesAdhocMetadata
                    LaunchedEffect(s, canAutoAuthorize) {
                        if (canAutoAuthorize && !autoAuthorized) {
                            autoAuthorized = true
                            // Assignments are already in the request's queryId order.
                            authorize(s.candidates.first().assignments)
                        }
                    }

                    if (canAutoAuthorize) {
                        // The biometric prompt is the second confirmation step; the wallet
                        // shouldn't repeat the merchant/amount card that the matcher already
                        // showed. Render a thin progress hint so something is on screen if
                        // the system delays the biometric sheet.
                        LoadingState(label = stringResource(R.string.present_loading))
                    } else {
                        ResolvedContent(
                            resolved = s,
                            credentialsById = credentialsById,
                            metadataResolver = metadataResolver,
                            compatibilityChecker = compatibilityChecker,
                            settings = settings,
                            onCancel = onCancel,
                            onAuthorize = authorize,
                            onDynamicScreenTitle = { dynamicScreenTitle = it },
                            onDisplayLocale = client::recordDisplayLocale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Terminal refusal for a `transaction_data` entry whose ad-hoc `metadata` JWT failed
 * verification (paso-proof-metadata.md §5.3).
 *
 * There is deliberately no affirmative action here. §5.3 makes such an entry incompatible
 * and forbids falling back to the stored credential metadata, so there is nothing the
 * wallet can honestly show the user to approve — the details on screen would be exactly
 * the ones whose provenance could not be established. Offering "continue anyway" would
 * hand the decision to the person least equipped to make it.
 *
 * Uses `errorContainer` rather than the `tertiaryContainer` of [SecurityHintBanner]: this
 * IS the stop signal, in the same severity tier as `UntrustedNotice`. The raw type URN is
 * shown as a secondary line because it is the one string that makes an issuer
 * misconfiguration diagnosable from a screenshot.
 */
@Composable
private fun IncompatibleTransactionContent(
    verifierName: String,
    entryType: String,
    onCancel: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GppBad,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.present_incompatible_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = stringResource(R.string.present_incompatible_body, verifierName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.present_incompatible_type, entryType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.present_cancel))
        }
    }
}

@Composable
private fun LoadingState(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResolvedContent(
    resolved: PresentationClient.State.Resolved,
    credentialsById: Map<String, Credential>,
    metadataResolver: TransactionMetadataResolver,
    compatibilityChecker: TransactionDataCompatibilityChecker,
    settings: SettingsRepository,
    onCancel: () -> Unit,
    onAuthorize: (List<DcqlMatcher.Match>) -> Unit,
    onDynamicScreenTitle: (String?) -> Unit,
    /** Reports the PaSO View §4 selected locale so it can be signed into `display_locale`. */
    onDisplayLocale: (String?) -> Unit,
) {
    val scroll = rememberScrollState()
    // Each candidate is a complete, spec-valid assignment of credentials to the
    // request's credential queries (OpenID4VP 1.0 §6.4.2, resolved in
    // DcqlCandidateResolver). One carousel page per candidate means the page the user
    // is looking at IS the selection — there is no separate per-query pick to track.
    // Candidate 0 is the verifier's most-preferred option.
    val pagerState = rememberPagerState(pageCount = { resolved.candidates.size })
    val selectedCandidate = resolved.candidates.getOrNull(pagerState.currentPage)
    val hasCandidate = selectedCandidate != null

    // Locale for resolving issuer-supplied claim labels and ui_labels. Recomputes
    // when the user changes language via Settings (SettingsViewModel.recreate
    // triggers a recomposition).
    val languagePref by settings.languagePreference.collectAsState(
        initial = dev.digitallabor.elpaso.wallet.data.settings.LanguagePreference.System,
    )
    val locale = remember(languagePref) { LocaleApplier.effectiveLocale(languagePref) }
    // First credential of the visible candidate. Because this feeds the
    // LaunchedEffect below, swiping the carousel re-resolves issuer metadata: the
    // transaction_data block, the dynamic screen title and the affirmative button
    // label all follow the credential the user is actually about to disclose.
    val sourceCredential: Credential? =
        selectedCandidate
            ?.assignments
            ?.firstNotNullOfOrNull { credentialsById[it.credentialId] }
    // Pre-validated render plans, keyed by the entry's verbatim base64url string rather
    // than by type: ad-hoc metadata is scoped to its own entry (paso-proof-metadata.md
    // §5.4), so two entries sharing a type must not share one entry's issuer-signed labels.
    // Re-runs when the selected credential changes, so swiping the carousel re-validates
    // against the credential the user is actually about to disclose.
    val plans =
        remember(resolved, sourceCredential, locale) {
            mutableStateOf<Map<String, RenderPlan>>(emptyMap())
        }
    // Non-null once any entry has been refused. Two different rules land here and both are
    // refusals rather than degraded renders: an ad-hoc `metadata` JWT that failed §5.3, and
    // an entry whose metadata or payload violates the rendering constraints of PaSO View
    // §2–§4.
    val refusal =
        remember(resolved, sourceCredential, locale) {
            mutableStateOf<ConsentRefusal?>(null)
        }
    LaunchedEffect(resolved, sourceCredential, locale) {
        val src =
            sourceCredential ?: run {
                plans.value = emptyMap()
                refusal.value = null
                onDynamicScreenTitle(null)
                onDisplayLocale(null)
                Log.d("PresentScreen", "no source credential; clearing dynamic title")
                return@LaunchedEffect
            }
        val byEntry =
            when (val outcome = metadataResolver.resolve(resolved.transactionData, src, locale)) {
                is TransactionMetadataResolver.Outcome.Incompatible -> {
                    Log.w("PresentScreen", "refusing presentation: ${outcome.entryType} — ${outcome.reason}")
                    plans.value = emptyMap()
                    refusal.value = ConsentRefusal(outcome.entryType, outcome.reason)
                    onDynamicScreenTitle(null)
                    onDisplayLocale(null)
                    return@LaunchedEffect
                }

                is TransactionMetadataResolver.Outcome.Resolved -> outcome.byEntry
            }

        // Every entry that has metadata must pass §7.4.2 step 2 before anything is drawn.
        // One incompatible entry refuses the whole request: the user consents once, to the
        // request as a whole, so rendering the rest and dropping this one would misrepresent
        // what is being approved.
        // PaSO View §4 takes an ordered list, not a single locale: the wallet tries each
        // language in turn and only settles on one that every display array can serve.
        val priority = LocaleSelector.localePriorityList(listOf(locale), Locale.ENGLISH)
        val validated = mutableMapOf<String, RenderPlan>()
        for (entry in resolved.transactionData) {
            val metadata = byEntry[entry.raw] ?: continue
            val payload = entry.payloadScope ?: continue
            when (val result = compatibilityChecker.check(metadata, payload, priority)) {
                is ValidationResult.Incompatible -> {
                    // The reason code is logged, never shown: a verifier must not learn
                    // which of its labels tripped which limit.
                    Log.w(
                        "PresentScreen",
                        "entry ${entry.type} is not compatible: ${result.reason.code} — ${result.reason.detail}",
                    )
                    plans.value = emptyMap()
                    refusal.value = ConsentRefusal(entry.type, result.reason.code.name)
                    onDynamicScreenTitle(null)
                    onDisplayLocale(null)
                    return@LaunchedEffect
                }

                is ValidationResult.Compatible -> validated[entry.raw] = result.plan
            }
        }

        refusal.value = null
        plans.value = validated
        // §4's outcome is what gets signed into `display_locale`: it describes the screen
        // the user actually approved, which is not necessarily the language they chose in
        // Settings. Reported from the same entry whose title the app bar shows.
        onDisplayLocale(
            resolved.transactionData.firstNotNullOfOrNull { entry -> validated[entry.raw]?.selectedLocaleTag },
        )
        Log.d("PresentScreen", "plans built for credential=${src.id} entries=${validated.size}")
        // Lift transaction_title up to the screen-level app bar (paso-proof-metadata.md
        // §3.2 — "Title for the consent screen"). Use the first entry that produced a plan;
        // that's the entry the user is consenting to.
        val title =
            resolved.transactionData
                .firstNotNullOfOrNull { entry -> validated[entry.raw]?.title }
                ?.plainText()
                ?.takeIf { it.isNotBlank() }
        Log.d("PresentScreen", "dynamic title resolved to: $title")
        onDynamicScreenTitle(title)
    }

    // Issuer-supplied action labels (paso-proof-metadata.md §3.2). Take the label
    // from the first transaction_data entry that has matching metadata — that's
    // the entry the user is consenting to. Falls back to the type-specific
    // hardcoded label when no metadata is available. value_type is honoured per
    // §3.2 — mini_markdown / template:* in button labels are rendered with
    // AnnotatedString.
    //
    // Hoisted above the layout branch below because the payment screen and the
    // general consent screen share the same action row.
    val primaryPlan: RenderPlan? =
        resolved.transactionData.firstNotNullOfOrNull { plans.value[it.raw] }
    val dynamicAffirmative: RenderedLabel? = primaryPlan?.affirmativeLabel
    val dynamicDenial: RenderedLabel? = primaryPlan?.denialLabel
    val authorizeFallback = stringResource(authorizeLabelFor(resolved.transactionData))

    // A payment request gets a purpose-built screen: amount and payee as the hero, the
    // paying card in a carousel, and none of the claim-path detail below. Non-payment
    // requests keep that detail — for attribute sharing it *is* the consent record,
    // whereas a payer approving an amount to a merchant is answering a different question.
    // §5.3: an entry whose `metadata` parameter fails verification is incompatible, and
    // the wallet SHALL NOT fall back to stored metadata for it. Checked before the payment
    // branch so a payment entry cannot slip past on the purpose-built screen — that screen
    // renders amount and payee from the entry itself, which is exactly the data the failed
    // signature was supposed to vouch for.
    refusal.value?.let { failure ->
        IncompatibleTransactionContent(
            verifierName = resolved.verifier.displayLabel,
            entryType = failure.entryType,
            onCancel = onCancel,
        )
        return
    }

    val paymentSummary = remember(resolved) { paymentSummaryOf(resolved.transactionData) }
    if (paymentSummary != null) {
        PaymentConsentContent(
            summary = paymentSummary,
            verifierName = resolved.verifier.displayLabel,
            trusted = resolved.verifier.trusted,
            candidates = resolved.candidates,
            credentialsById = credentialsById,
            pagerState = pagerState,
            locale = locale,
            authorizeLabel = dynamicAffirmative,
            authorizeFallback = authorizeFallback,
            denialLabel = dynamicDenial,
            onAuthorize = { selectedCandidate?.let { onAuthorize(it.assignments) } },
            onCancel = onCancel,
        )
        return
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        RequesterLine(
            verifierName = resolved.verifier.displayLabel,
            trusted = resolved.verifier.trusted,
        )

        resolved.transactionData.forEach { td ->
            val plan = plans.value[td.raw]
            if (plan != null) {
                DynamicTransactionDataBlock(plan = plan)
            } else {
                // No issuer metadata covers this entry. That is not a violation — §3.2
                // makes `ui_labels` optional and an entry may simply predate PaSO
                // metadata — so the type-specific hardcoded renderer still applies.
                TransactionDataBlock(item = td)
            }
        }

        // The trust warning sits directly below the transaction_data section so the user
        // reads it in context with what they're consenting to — not as a footer disclaimer
        // tucked between the credential picker and the button. The "swipe to choose" nudge
        // is not a warning and belongs next to the carousel it describes, so it lives
        // inside CandidateCarousel instead of competing here.
        if (!resolved.verifier.trusted) {
            UntrustedNotice()
        }

        if (resolved.candidates.isEmpty()) {
            NoMatchNotice()
        } else {
            CandidateCarousel(
                candidates = resolved.candidates,
                credentialsById = credentialsById,
                pagerState = pagerState,
            )

            // Keyed off the visible page, so swiping the carousel re-renders this list and
            // it always describes the credentials actually about to be disclosed.
            selectedCandidate?.let { candidate ->
                SectionHeading(text = stringResource(R.string.present_fields))
                RequestedInformationSection(
                    candidate = candidate,
                    credentialsById = credentialsById,
                    locale = locale,
                )
            }
        }

        val authorizeEnabled = hasCandidate && resolved.verifier.trusted

        ActionRow(
            authorizeLabel = dynamicAffirmative,
            authorizeFallback = authorizeFallback,
            denialLabel = dynamicDenial,
            authorizeEnabled = authorizeEnabled,
            onAuthorize = {
                // The candidate's assignments are already in the request's queryId order,
                // so the verifier sees presentations in a predictable order when it
                // evaluates credential_sets.
                selectedCandidate?.let { onAuthorize(it.assignments) }
            },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * Why an entry was refused. [entryType] is the only part shown to the user; the reason is
 * carried for the log, since telling a verifier which constraint it tripped would help it
 * search for one the wallet does not check.
 */
private data class ConsentRefusal(
    val entryType: String,
    val reason: String,
)

@Composable
internal fun ActionRow(
    authorizeLabel: RenderedLabel?,
    authorizeFallback: String,
    authorizeEnabled: Boolean,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
    denialLabel: RenderedLabel? = null,
) {
    // Filled primary (authorize) + filled tonal (cancel) at L-size height. The
    // primary leads visually with shape-contrast against the rounder credential
    // cards above and the chip-shaped trust badge.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onCancel,
            modifier =
                Modifier
                    .weight(1f)
                    .height(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            ButtonLabel(
                label = denialLabel,
                fallback = stringResource(R.string.present_cancel),
            )
        }
        Button(
            enabled = authorizeEnabled,
            onClick = onAuthorize,
            modifier =
                Modifier
                    .weight(1f)
                    .height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            ButtonLabel(
                label = authorizeLabel,
                fallback = authorizeFallback,
            )
        }
    }
}

/**
 * An action button's caption: the issuer's `ui_labels` entry when one survived validation,
 * otherwise the wallet's own string.
 *
 * A [RenderedLabel] is only ever plain text or `mini_markdown` — §3.3 forbids the value
 * types that would produce an image or a link here — so unlike the formatter it replaces,
 * this has no unreachable branches to fall through.
 */
@Composable
private fun ButtonLabel(
    label: RenderedLabel?,
    fallback: String,
) {
    val style = MaterialTheme.typography.titleMedium
    if (label == null || label.plainText().isBlank()) {
        Text(text = fallback, style = style, fontWeight = FontWeight.SemiBold)
    } else {
        LabelText(label = label, style = style, fontWeight = FontWeight.SemiBold)
    }
}

private fun authorizeLabelFor(transactions: List<TransactionData>): Int =
    when {
        // Any PaSO type — whether the hardcoded `urn:paso:sca:global:payment:1` shape
        // or any new urn:paso:sca:* type the issuer declares — gets the payment-flavour
        // fallback so the button still reads "Pay" / "Bezahlen" when metadata isn't
        // available. The issuer-supplied affirmative_action_label always wins when present.
        transactions.any { it is TransactionData.PasoPayment || it.isPaso() } -> R.string.present_authorize_pay

        transactions.any { it is TransactionData.PaymentData } -> R.string.present_authorize_payment

        transactions.any { it is TransactionData.QesAuthorization } -> R.string.present_authorize_sign

        else -> R.string.present_authorize
    }

/**
 * The credential as a physical-feeling pass: issuer gradient, issuer logo, the credential's
 * name, and the issuer-masked account when it carries one — the same card the payment screen
 * shows, so a credential looks like itself wherever the wallet renders it.
 *
 * It deliberately carries no claim rows. Those live in [RequestedInformationSection] below
 * the carousel, and moving them there is what lets this card hold the ID-1 ratio: a
 * fixed-proportion card cannot grow to fit eight claim values, and clipping the very
 * information the user is consenting to is not an option.
 *
 * The card also carries no selection state — it lives on a carousel page, and the visible
 * page *is* the selection.
 */
@Composable
private fun MatchPassCard(
    match: DcqlMatcher.Match,
    credential: Credential?,
) {
    val shape = RoundedCornerShape(28.dp)
    // aspectRatio rather than a height: the pager page is already sized to the same ratio,
    // so the two agree by construction and the card keeps its proportions on any screen
    // width — including the narrower pages that appear once the peek kicks in.
    val baseModifier =
        Modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT_RATIO)
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)

    if (credential == null) {
        // The credential row hasn't loaded yet (or was deleted mid-flow). A neutral card at
        // the same ratio keeps the carousel's geometry stable instead of collapsing the page.
        Box(
            modifier = baseModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = match.credentialId.take(8),
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }

    val art = PassArt.forCredential(credential)
    val display = CredentialDisplay.resolve(credential)
    val maskedAccount = remember(credential.id) { maskedAccountOf(credential) }

    Box(modifier = baseModifier) {
        // Gradient + sheen only. Issuer-supplied background images often embed their own
        // logos/copy that collide with the credential name we render on top — skip them
        // here. Issuer colors still drive the gradient via PassArt.fromDisplay; the logo on
        // the top-right is preserved.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(brush = art.baseGradient),
        )
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(brush = art.sheenOverlay),
        )

        Column(
            modifier =
                Modifier
                    .matchParentSize()
                    .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = display.name,
                        color = art.foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    display.description?.let {
                        Text(
                            text = it,
                            color = art.foreground.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                display.logoUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = display.name,
                        modifier =
                            Modifier
                                .padding(start = 12.dp)
                                .size(36.dp),
                        onError = { Log.w("MatchPassCardLogo", "logo load failed for $uri", it.result.throwable) },
                    )
                }
            }

            maskedAccount?.let {
                Text(
                    text = it,
                    color = art.foreground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What the verifier will actually receive, for the candidate currently on screen. For an
 * attribute-sharing request this list *is* the consent record, so every requested claim path
 * is rendered with the value that would be disclosed — no truncation, no "and 3 more".
 *
 * When a candidate spans several credentials (DCQL `credential_sets`) each gets its own
 * labelled group, so the user can tell which credential contributes which attribute. With a
 * single credential the group heading is suppressed: it would only repeat the card above it.
 */
@Composable
private fun RequestedInformationSection(
    candidate: PresentationCandidate,
    credentialsById: Map<String, Credential>,
    locale: Locale,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val grouped = candidate.assignments.size > 1
            candidate.assignments.forEachIndexed { index, assignment ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                val credential = credentialsById[assignment.credentialId]
                if (grouped) {
                    Text(
                        text =
                            credential
                                ?.let { CredentialDisplay.resolve(it).name }
                                ?: assignment.credentialId.take(8),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RequestedClaimList(match = assignment, credential = credential, locale = locale)
            }
        }
    }
}

@Composable
private fun RequestedClaimList(
    match: DcqlMatcher.Match,
    credential: Credential?,
    locale: Locale,
) {
    if (match.requestedClaimPaths.isEmpty()) {
        // A query with no `claims` member asks for the whole credential rather than for
        // named attributes, so there is nothing to enumerate here.
        Text(
            text = stringResource(R.string.present_no_fields_requested),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val claims = remember(credential?.id) { credential?.let { CredentialClaims.extract(it) } }
    // Issuer-supplied labels for this credential — from its SD-JWT VC Type Metadata or,
    // failing that, from the OpenID4VCI issuer metadata captured at offer time. Both are
    // persisted in the same blob, so one lookup covers either source. Credentials issued
    // before claim metadata was captured resolve empty and fall back to the raw path.
    val labels =
        remember(credential?.id, locale) {
            credential?.let { ClaimLabelResolver.resolve(it.displayMetadataJson, locale) }
        }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        match.requestedClaimPaths.forEach { path ->
            ClaimRequestRow(
                label = labels?.labelFor(path) ?: path.joinToString("."),
                value =
                    claims?.let {
                        resolveClaim(it.user, path) ?: resolveClaim(it.protocol, path)
                    },
            )
        }
    }
}

@Composable
private fun ClaimRequestRow(
    label: String,
    value: JsonElement?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value?.let(::formatClaimValue) ?: "—",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.2f),
        )
    }
}

private fun resolveClaim(
    root: JsonObject,
    path: List<String>,
): JsonElement? {
    var node: JsonElement = root
    for (segment in path) {
        node = (node as? JsonObject)?.get(segment) ?: return null
    }
    return node
}

private fun formatClaimValue(value: JsonElement): String =
    when (value) {
        is JsonNull -> {
            "—"
        }

        is JsonPrimitive -> {
            value.content
        }

        is JsonArray -> {
            value.joinToString(", ") {
                if (it is JsonPrimitive) it.content else it.toString()
            }
        }

        is JsonObject -> {
            value.toString()
        }
    }

// Gap between the cards of a candidate that needs more than one credential. The card's own
// height is no longer a constant at all — it follows from the page width and
// CARD_ASPECT_RATIO, which is what removed the pile of layout constants that used to have to
// stay in sync with the card's internals.
private val CARD_STACK_SPACING = 12.dp

/**
 * One page per [PresentationCandidate]. The visible page is the selection, so there is no
 * tap-to-select affordance — swiping (or tapping a dot) is how the user chooses.
 *
 * A pager rather than M3's HorizontalMultiBrowseCarousel: Material's carousel guidance
 * says text-heavy items should use a series of cards instead, and the size-morphing
 * carousel layouts would clip the claim values the user must read before consenting.
 */
@Composable
private fun CandidateCarousel(
    candidates: List<PresentationCandidate>,
    credentialsById: Map<String, Credential>,
    pagerState: PagerState,
) {
    // A peek of the neighbouring card is what signals "there is more to swipe through";
    // with a single candidate there is nothing to peek at, so the page runs full width.
    val peek = if (candidates.size > 1) 24.dp else 0.dp
    // Every page is as tall as the busiest candidate so the pager doesn't resize mid-swipe.
    val maxCardsPerPage = candidates.maxOf { it.assignments.size }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The pager sits in a verticalScroll and so receives unbounded vertical
        // constraints — it needs an explicit height. Deriving that height from the width a
        // page actually gets (the peek narrows every page on both sides) is what holds the
        // cards at the ID-1 ratio instead of letterboxing them inside a taller page.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardHeight = (maxWidth - peek * 2) / CARD_ASPECT_RATIO
            val pageHeight =
                cardHeight * maxCardsPerPage +
                    CARD_STACK_SPACING * (maxCardsPerPage - 1).coerceAtLeast(0)
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(pageHeight),
                contentPadding = PaddingValues(horizontal = peek),
                pageSpacing = 12.dp,
            ) { page ->
                val candidate = candidates[page]
                val position =
                    stringResource(
                        R.string.present_candidate_position,
                        page + 1,
                        candidates.size,
                    )
                Column(
                    modifier = Modifier.semantics { contentDescription = position },
                    verticalArrangement = Arrangement.spacedBy(CARD_STACK_SPACING),
                ) {
                    candidate.assignments.forEach { assignment ->
                        MatchPassCard(
                            match = assignment,
                            credential = credentialsById[assignment.credentialId],
                        )
                    }
                }
            }
        }

        if (candidates.size > 1) {
            // The nudge lives here rather than as a card further up the screen: it describes
            // the carousel, so it reads as an instruction next to it and as a disclaimer
            // anywhere else. Same treatment as the payment screen's card picker.
            Text(
                text = stringResource(R.string.present_choose_credential),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            CandidateDots(
                count = candidates.size,
                selected = pagerState.currentPage,
                onSelect = { pagerState.requestScrollToPage(it) },
            )
        }
    }
}

/**
 * Page indicator that doubles as the non-swipe path to every candidate. Material requires
 * carousels on vertically-scrolling pages to be reachable without horizontal scrolling; we
 * satisfy that with tappable dots rather than a "Show all" screen, because the candidate
 * count here is two or three rather than ten. Each dot carries a 48dp touch target.
 */
@Composable
internal fun CandidateDots(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == selected
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                label = "candidateDotWidth",
            )
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clickable(
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(width)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                },
                            ),
                )
            }
        }
    }
}
