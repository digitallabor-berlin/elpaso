package dev.digitallabor.elpaso.wallet.ui.present

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.pick
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.PresentationClient
import dev.digitallabor.elpaso.wallet.presentation.txdata.DynamicTransactionDataBlock
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionDataBlock
import dev.digitallabor.elpaso.wallet.presentation.txdata.ValueTypeFormatters
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
    val credentialMetadataRepository: CredentialMetadataRepository = koinInject()
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
                selectedCredentialId = route.preselectedCredentialId,
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
                if (s.transactionData.any {
                        it is TransactionData.PaymentData ||
                            it is TransactionData.PasoPayment
                    }
                ) {
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
                    suspend fun promptSignature(
                        match: DcqlMatcher.Match,
                        skipPromptIfUnlocked: Boolean,
                    ): java.security.Signature =
                        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                            biometric.authorize(
                                activity = context as FragmentActivity,
                                deviceKeyAlias = "cred_${match.credentialId}",
                                skipPromptIfUnlocked = skipPromptIfUnlocked,
                                onAuthorized = { sig ->
                                    if (cont.isActive) cont.resumeWith(Result.success(sig))
                                },
                                onError = { msg ->
                                    if (cont.isActive) cont.resumeWith(Result.failure(SecurityException(msg)))
                                },
                            )
                        }

                    val authorize: (List<DcqlMatcher.Match>) -> Unit = { matches ->
                        scope.launch {
                            try {
                                val pairs =
                                    matches.map { m ->
                                        m to promptSignature(m, route.systemPreAuthBiometric)
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
                    val canAutoAuthorize =
                        isDcApi &&
                            (s.verifier.trusted || developerMode) &&
                            s.candidates.size == 1
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
                            credentialMetadataRepository = credentialMetadataRepository,
                            settings = settings,
                            onCancel = onCancel,
                            onAuthorize = authorize,
                            onDynamicScreenTitle = { dynamicScreenTitle = it },
                        )
                    }
                }
            }
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
    credentialMetadataRepository: CredentialMetadataRepository,
    settings: SettingsRepository,
    onCancel: () -> Unit,
    onAuthorize: (List<DcqlMatcher.Match>) -> Unit,
    onDynamicScreenTitle: (String?) -> Unit,
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
    // Per-entry metadata lookup, re-runs when the selected credential changes so
    // switching credentials updates the consent block live (additive — when no
    // metadata is found we fall back to the hardcoded renderer).
    val dynamicMetadata =
        remember(resolved, sourceCredential, locale) {
            mutableStateOf<Map<String, TransactionDataTypeMetadata>>(emptyMap())
        }
    LaunchedEffect(resolved, sourceCredential, locale) {
        val src =
            sourceCredential ?: run {
                dynamicMetadata.value = emptyMap()
                onDynamicScreenTitle(null)
                Log.d("PresentScreen", "no source credential; clearing dynamic title")
                return@LaunchedEffect
            }
        val byType =
            resolved.transactionData
                .map { it.type }
                .distinct()
                .associateWith { type ->
                    credentialMetadataRepository.getTransactionDataType(src, locale, type)
                }.filterValues { it != null }
                .mapValues { it.value!! }
        dynamicMetadata.value = byType
        Log.d("PresentScreen", "metadata loaded for credential=${src.id} types=${byType.keys}")
        // Lift transaction_title up to the screen-level app bar (paso-proof-metadata.md
        // §3.2 — "Title for the consent screen"). Use the first transaction_data entry
        // that resolved against metadata; that's the entry the user is consenting to.
        val title =
            resolved.transactionData
                .firstNotNullOfOrNull { entry ->
                    val md = byType[entry.type] ?: return@firstNotNullOfOrNull null
                    val label = md.uiLabels.transactionTitle.pick(locale) ?: return@firstNotNullOfOrNull null
                    val formatted =
                        dev.digitallabor.elpaso.wallet.presentation.txdata.UiLabelRenderer
                            .resolve(label, md, entry.payloadScope, locale)
                    when (formatted) {
                        is ValueTypeFormatters.Formatted.PlainText -> formatted.text
                        is ValueTypeFormatters.Formatted.MiniMarkdown -> formatted.text
                        is ValueTypeFormatters.Formatted.Url -> formatted.href
                        else -> null
                    }?.takeIf { it.isNotBlank() }
                }
        Log.d("PresentScreen", "dynamic title resolved to: $title")
        onDynamicScreenTitle(title)
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
        VerifierHeroCard(
            label = stringResource(R.string.present_verifier),
            verifierName = resolved.verifier.displayLabel,
            trusted = resolved.verifier.trusted,
            untrustedWarning = stringResource(R.string.present_unknown_verifier),
        )

        resolved.transactionData.forEach { td ->
            val metadata = dynamicMetadata.value[td.type]
            if (metadata != null) {
                DynamicTransactionDataBlock(item = td, metadata = metadata, locale = locale)
            } else {
                TransactionDataBlock(item = td)
            }
        }

        // Gate hint sits directly below the transaction_data section so the user
        // reads the warning in context with what they're consenting to — not as a
        // footer disclaimer tucked between the credential picker and the button.
        // Untrusted verifier is a hard error (errorContainer); scroll/pick are soft
        // nudges (surfaceContainerHigh) so they don't compete with the trust warning.
        when {
            !resolved.verifier.trusted -> {
                GateHintCard(
                    icon = Icons.Filled.Warning,
                    text = stringResource(R.string.present_unknown_verifier),
                    severity = GateHintSeverity.Error,
                )
            }

            resolved.candidates.size > 1 -> {
                GateHintCard(
                    icon = Icons.Filled.TouchApp,
                    text = stringResource(R.string.present_choose_credential),
                    severity = GateHintSeverity.Info,
                )
            }
        }

        if (resolved.candidates.isEmpty()) {
            NoMatchCard(text = stringResource(R.string.present_no_match))
        } else {
            SectionHeading(text = stringResource(R.string.present_fields))
            CandidateCarousel(
                candidates = resolved.candidates,
                credentialsById = credentialsById,
                pagerState = pagerState,
            )
        }

        val authorizeEnabled = hasCandidate && resolved.verifier.trusted

        // Issuer-supplied action labels (paso-proof-metadata.md §3.2). Take the label
        // from the first transaction_data entry that has matching metadata — that's
        // the entry the user is consenting to. Falls back to the type-specific
        // hardcoded label when no metadata is available. value_type is honoured per
        // §3.2 — mini_markdown / template:* in button labels are rendered with
        // AnnotatedString.
        val primaryDynamicLabels: TransactionDataTypeMetadata? =
            resolved.transactionData
                .firstNotNullOfOrNull { dynamicMetadata.value[it.type] }
        val primaryPayload =
            resolved.transactionData
                .firstOrNull { dynamicMetadata.value[it.type] != null }
                ?.payloadScope
        val dynamicAffirmative: ValueTypeFormatters.Formatted? =
            primaryDynamicLabels
                ?.uiLabels
                ?.affirmativeActionLabel
                ?.pick(locale)
                ?.let {
                    dev.digitallabor.elpaso.wallet.presentation.txdata.UiLabelRenderer.resolve(
                        it,
                        primaryDynamicLabels,
                        primaryPayload,
                        locale,
                    )
                }
        val dynamicDenial: ValueTypeFormatters.Formatted? =
            primaryDynamicLabels
                ?.uiLabels
                ?.denialActionLabel
                ?.pick(locale)
                ?.let {
                    dev.digitallabor.elpaso.wallet.presentation.txdata.UiLabelRenderer.resolve(
                        it,
                        primaryDynamicLabels,
                        primaryPayload,
                        locale,
                    )
                }

        ActionRow(
            authorizeLabel = dynamicAffirmative,
            authorizeFallback = stringResource(authorizeLabelFor(resolved.transactionData)),
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
private fun VerifierHeroCard(
    label: String,
    verifierName: String,
    trusted: Boolean,
    untrustedWarning: String,
) {
    // Compact identity card — the transaction_data block below is the hero, so
    // the verifier just needs to answer "who is asking" without dominating. Trust
    // state stays as a chip so warnings remain glanceable. 20dp corners match
    // the other tonal cards on screen for one consistent shape language.
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Text(
                text = verifierName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            TrustBadge(trusted = trusted, untrustedWarning = untrustedWarning)
        }
    }
}

@Composable
private fun TrustBadge(
    trusted: Boolean,
    untrustedWarning: String,
) {
    val container =
        if (trusted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    val onContainer =
        if (trusted) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }
    val icon = if (trusted) Icons.Filled.Verified else Icons.Filled.Warning
    val text =
        if (trusted) {
            stringResource(R.string.present_verifier_trusted)
        } else {
            untrustedWarning
        }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                color = onContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NoMatchCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private enum class GateHintSeverity { Error, Info }

@Composable
private fun GateHintCard(
    icon: ImageVector,
    text: String,
    severity: GateHintSeverity,
) {
    val container =
        when (severity) {
            GateHintSeverity.Error -> MaterialTheme.colorScheme.errorContainer
            GateHintSeverity.Info -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val onContainer =
        when (severity) {
            GateHintSeverity.Error -> MaterialTheme.colorScheme.onErrorContainer
            GateHintSeverity.Info -> MaterialTheme.colorScheme.onSurface
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = text,
                color = onContainer,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

@Composable
private fun ActionRow(
    authorizeLabel: ValueTypeFormatters.Formatted?,
    authorizeFallback: String,
    authorizeEnabled: Boolean,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
    denialLabel: ValueTypeFormatters.Formatted? = null,
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
            FormattedButtonLabel(
                formatted = denialLabel,
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
            FormattedButtonLabel(
                formatted = authorizeLabel,
                fallback = authorizeFallback,
            )
        }
    }
}

@Composable
private fun FormattedButtonLabel(
    formatted: ValueTypeFormatters.Formatted?,
    fallback: String,
) {
    val style = MaterialTheme.typography.titleMedium
    when (formatted) {
        is ValueTypeFormatters.Formatted.MiniMarkdown -> {
            Text(
                text =
                    dev.digitallabor.elpaso.wallet.presentation.txdata
                        .renderMiniMarkdown(formatted.text),
                style = style,
                fontWeight = FontWeight.SemiBold,
            )
        }

        is ValueTypeFormatters.Formatted.PlainText -> {
            Text(
                text = formatted.text.ifBlank { fallback },
                style = style,
                fontWeight = FontWeight.SemiBold,
            )
        }

        is ValueTypeFormatters.Formatted.Url -> {
            Text(
                text = formatted.href,
                style = style,
                fontWeight = FontWeight.SemiBold,
            )
        }

        is ValueTypeFormatters.Formatted.Image,
        is ValueTypeFormatters.Formatted.LabelOnly,
        null,
        -> {
            Text(
                text = fallback,
                style = style,
                fontWeight = FontWeight.SemiBold,
            )
        }
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

@Composable
private fun MatchPassCard(
    match: DcqlMatcher.Match,
    credential: Credential?,
) {
    // The card no longer carries selection state: it lives on a carousel page, and the
    // visible page is the selection. 28dp corners match the wallet's pass shape; the
    // dots below the pager, not a border, communicate which option is active.
    val shape = RoundedCornerShape(28.dp)

    val baseModifier =
        Modifier
            .fillMaxWidth()
            .heightIn(min = CARD_MIN_HEIGHT)
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)

    if (credential == null) {
        Box(
            modifier = baseModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = match.credentialId.take(8),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${match.format.name} · ${match.requestedClaimPaths.size} field(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val art = PassArt.forCredential(credential)
    val display = CredentialDisplay.resolve(credential)
    val claims = remember(credential.id) { CredentialClaims.extract(credential) }

    Box(modifier = baseModifier) {
        // Gradient + sheen only. Issuer-supplied background images often embed their own
        // logos/copy that collide with the credential name + the claim values we render
        // on top — skip them here. Issuer colors still drive the gradient via
        // PassArt.fromDisplay; the logo on the top-right is preserved.
        //
        // matchParentSize() (BoxScope) is required because the parent has unbounded
        // vertical constraints (heightIn(min=...) inside a verticalScroll) — fillMaxSize()
        // would collapse the layers to 0dp and the gradient/sheen would never paint. With
        // matchParentSize, the Column below determines the height and the layers stretch
        // to match after measurement.
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
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = display.name,
                        color = art.foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
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

            HorizontalDivider(color = art.foreground.copy(alpha = 0.25f))

            if (match.requestedClaimPaths.isEmpty()) {
                Text(
                    text = "${match.format.name} · 0 field(s)",
                    color = art.foreground.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    match.requestedClaimPaths.forEach { path ->
                        ClaimRequestRow(
                            path = path,
                            value =
                                resolveClaim(claims.user, path)
                                    ?: resolveClaim(claims.protocol, path),
                            foreground = art.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimRequestRow(
    path: List<String>,
    value: JsonElement?,
    foreground: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = path.joinToString("."),
            color = foreground.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value?.let(::formatClaimValue) ?: "—",
            color = foreground,
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

// Card metrics shared between MatchPassCard and the pager that sizes it. HorizontalPager
// inside a verticalScroll receives unbounded vertical constraints, so it needs an explicit
// height; deriving that height from the same constants the card lays itself out with is
// what stops the two drifting apart when padding changes.
private val CARD_MIN_HEIGHT = 132.dp
private val CARD_PADDING = 18.dp
private val CARD_CONTENT_SPACING = 10.dp
private val CARD_HEADER_HEIGHT = 44.dp
private val CARD_DIVIDER_HEIGHT = 1.dp
private val CLAIM_ROW_HEIGHT = 40.dp
private val CLAIM_ROW_SPACING = 6.dp
private val CARD_STACK_SPACING = 12.dp

private fun estimatedCardHeight(claimCount: Int): Dp {
    val claims =
        if (claimCount == 0) {
            CLAIM_ROW_HEIGHT
        } else {
            CLAIM_ROW_HEIGHT * claimCount + CLAIM_ROW_SPACING * (claimCount - 1)
        }
    val total =
        CARD_PADDING * 2 +
            CARD_HEADER_HEIGHT +
            CARD_CONTENT_SPACING +
            CARD_DIVIDER_HEIGHT +
            CARD_CONTENT_SPACING +
            claims
    return if (total < CARD_MIN_HEIGHT) CARD_MIN_HEIGHT else total
}

private fun estimatedCandidateHeight(candidate: PresentationCandidate): Dp {
    val cards = candidate.assignments.map { estimatedCardHeight(it.requestedClaimPaths.size) }
    val stacked = cards.fold(0.dp) { acc, h -> acc + h }
    val gaps = if (cards.size > 1) CARD_STACK_SPACING * (cards.size - 1) else 0.dp
    return stacked + gaps
}

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
    val pageHeight = candidates.maxOf { estimatedCandidateHeight(it) }
    // A peek of the neighbouring card is what signals "there is more to swipe through";
    // with a single candidate there is nothing to peek at, so the page runs full width.
    val peek = if (candidates.size > 1) 24.dp else 0.dp

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

        if (candidates.size > 1) {
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
private fun CandidateDots(
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
