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
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.dcapi.DcApiSelection
import dev.digitallabor.elpaso.wallet.domain.claims.ClaimLabelResolver
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

    // Issuer-supplied action labels (paso-proof-metadata.md §3.2). Take the label
    // from the first transaction_data entry that has matching metadata — that's
    // the entry the user is consenting to. Falls back to the type-specific
    // hardcoded label when no metadata is available. value_type is honoured per
    // §3.2 — mini_markdown / template:* in button labels are rendered with
    // AnnotatedString.
    //
    // Hoisted above the layout branch below because the payment screen and the
    // general consent screen share the same action row.
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
    val authorizeFallback = stringResource(authorizeLabelFor(resolved.transactionData))

    // A payment request gets a purpose-built screen: amount and payee as the hero, the
    // paying card in a carousel, and none of the claim-path detail below. Non-payment
    // requests keep that detail — for attribute sharing it *is* the consent record,
    // whereas a payer approving an amount to a merchant is answering a different question.
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
            val metadata = dynamicMetadata.value[td.type]
            if (metadata != null) {
                DynamicTransactionDataBlock(item = td, metadata = metadata, locale = locale)
            } else {
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

@Composable
internal fun ActionRow(
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
