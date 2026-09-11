package dev.digitallabor.elpaso.wallet.ui.present

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.domain.claims.ClaimLabelResolver
import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import dev.digitallabor.elpaso.wallet.presentation.txdata.formatIsoCurrencyAmount
import dev.digitallabor.elpaso.wallet.presentation.txdata.SecurityHintBanner
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderedLabel
import dev.digitallabor.elpaso.wallet.ui.common.TrustWarningCard
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * What a payment consent screen needs to show, distilled from a `transaction_data` entry:
 * an amount and who is being paid. Everything else the entry carries — payee identifiers,
 * transaction references, hash algorithms — is protocol bookkeeping the payer cannot act
 * on, so it is deliberately not part of this model.
 */
internal data class PaymentSummary(
    val amount: String,
    val payee: String?,
    /**
     * `credential_ids` from the entry — the DCQL query ids the payment is targeted at, used
     * to tell the paying instrument apart from credentials merely requested alongside it.
     * Empty for entry types that carry no targeting, in which case position decides.
     */
    val payingQueryIds: List<String>,
)

/**
 * The claim carrying a display-safe account identifier, already masked by the issuer
 * (e.g. `CZ55***********8745`). Shown on the payment card so the payer can tell *which*
 * account they are about to pay with — the one piece of credential detail this screen keeps.
 *
 * Credentials that don't carry it (notably `com.emvco.dpc.card`, whose claim vocabulary is
 * not yet wired up) simply render without the line rather than showing a placeholder.
 */
private const val MASKED_ACCOUNT_CLAIM = "masked_iban"

/**
 * ID-1 aspect ratio (ISO/IEC 7810 — 85.60 × 53.98 mm). A credential card is a stand-in for a
 * physical card, so it carries the physical proportions rather than an arbitrary height.
 *
 * Shared with the general consent screen in `PresentScreen.kt` (same package) so both screens'
 * cards are proportioned identically by construction rather than by two constants agreeing.
 */
internal const val CARD_ASPECT_RATIO = 1.586f

/**
 * Returns the payment summary for the first payment-flavoured entry in the request, or null
 * when this is not a payment request.
 *
 * All three payment types resolve here so the wallet has exactly one payment screen: the
 * EUDI SCA type, the PaSO type, and the legacy `payment_data` type. Amount handling differs
 * per type only in how much formatting is safe — [TransactionData.EudiScaPayment.amountDisplay]
 * is already formatted for the payer and is passed through untouched.
 */
internal fun paymentSummaryOf(entries: List<TransactionData>): PaymentSummary? =
    entries.firstNotNullOfOrNull { entry ->
        when (entry) {
            is TransactionData.EudiScaPayment -> {
                PaymentSummary(
                    amount = entry.amountDisplay,
                    payee = entry.payeeName,
                    payingQueryIds = entry.credentialIds,
                )
            }

            is TransactionData.PasoPayment -> {
                PaymentSummary(
                    amount = formatIsoCurrencyAmount(entry.amount, entry.currency, entry.amountRaw),
                    payee = entry.payeeName,
                    payingQueryIds = emptyList(),
                )
            }

            is TransactionData.PaymentData -> {
                val amount =
                    listOfNotNull(entry.currency, entry.amount)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() }
                amount?.let {
                    PaymentSummary(amount = it, payee = entry.payeeName, payingQueryIds = emptyList())
                }
            }

            else -> {
                null
            }
        }
    }

/**
 * Splits a candidate into the credential that pays and the credentials the verifier asked
 * for alongside it.
 *
 * A DCQL candidate is a complete assignment — one credential per credential query — so a
 * payment request that also asks for, say, an age attestation produces a candidate holding
 * both. [payingQueryIds] (the payment entry's `credential_ids`) names which query is the
 * payment one; when the entry carries no targeting, or names a query this candidate does not
 * answer, the first assignment is taken as the payer, matching the request order the verifier
 * sent. The remainder keep their query order so the screen lists them as the verifier did.
 *
 * Pure Kotlin on purpose: no credential bytes are touched, so the choice stays unit-testable
 * on the JVM where `android.util.*` is stubbed out.
 */
internal fun splitAssignments(
    candidate: PresentationCandidate,
    payingQueryIds: List<String>,
): Pair<DcqlMatcher.Match?, List<DcqlMatcher.Match>> {
    val paying =
        candidate.assignments.firstOrNull { it.queryId in payingQueryIds }
            ?: candidate.assignments.firstOrNull()
            ?: return null to emptyList()
    return paying to candidate.assignments.filter { it !== paying }
}

/**
 * Reads the issuer-masked account identifier off a credential. Returns null when the claim
 * is absent or is not a string, so the caller can omit the line entirely.
 */
internal fun maskedAccountOf(credential: Credential): String? =
    runCatching {
        (CredentialClaims.extract(credential).user[MASKED_ACCOUNT_CLAIM] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

/**
 * The payment consent screen: amount, payee, who is asking, which card pays, approve/deny.
 *
 * Deliberately carries no claim-path rows, no format names and no credential identifiers —
 * a payer approving a specific amount to a specific merchant is answering a different
 * question than a user deciding whether to disclose a set of attributes. The general
 * data-sharing consent screen in `PresentScreen` keeps that detail; this one does not.
 */
@Composable
internal fun PaymentConsentContent(
    summary: PaymentSummary,
    verifierName: String,
    trusted: Boolean,
    candidates: List<PresentationCandidate>,
    credentialsById: Map<String, Credential>,
    pagerState: PagerState,
    locale: Locale,
    authorizeLabel: RenderedLabel?,
    authorizeFallback: String,
    denialLabel: RenderedLabel?,
    /**
     * The issuer's `security_hint` for this transaction, or null when the metadata carries
     * none. PaSO View §2 counts it among the "populated UI elements" that must have been
     * displayed before the confirmation action is enabled.
     */
    securityHint: String?,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    val scroll = rememberScrollState()
    // Hoisted rather than inlined into the `&&` below: a composable behind a short-circuit
    // is only invoked when the earlier operands hold, which would make the scroll
    // observation come and go with the verifier's trust state.
    val contentReviewed = rememberContentReviewed(scroll)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        AmountHero(summary = summary)

        RequesterLine(verifierName = verifierName, trusted = trusted)

        // Sits above the untrusted notice so the two warnings stack by severity, the same
        // order the generic consent screen uses. Rendered with the shared banner rather
        // than a payment-specific one: an issuer's hint must look identical wherever the
        // wallet shows it, or the visual difference itself becomes something to imitate
        // (§5.4, wallet chrome spoofing).
        securityHint?.let { SecurityHintBanner(it) }

        if (!trusted) {
            UntrustedNotice()
        }

        if (candidates.isEmpty()) {
            NoMatchNotice()
        } else {
            PaymentCardCarousel(
                candidates = candidates,
                payingQueryIds = summary.payingQueryIds,
                credentialsById = credentialsById,
                pagerState = pagerState,
            )

            // Keyed off the visible page, so swiping the carousel re-renders this and it
            // always describes the credentials the selected candidate actually discloses.
            val supporting =
                candidates
                    .getOrNull(pagerState.currentPage)
                    ?.let { splitAssignments(it, summary.payingQueryIds).second }
                    .orEmpty()
            if (supporting.isNotEmpty()) {
                SupportingDisclosures(
                    matches = supporting,
                    credentialsById = credentialsById,
                    locale = locale,
                )
            }
        }

        ActionRow(
            authorizeLabel = authorizeLabel,
            authorizeFallback = authorizeFallback,
            denialLabel = denialLabel,
            // §2: the confirmation action is enabled only once the content — the hint
            // included — has actually been displayed. See `rememberContentReviewed`.
            authorizeEnabled = candidates.isNotEmpty() && trusted && contentReviewed,
            onAuthorize = onAuthorize,
            onCancel = onCancel,
        )
    }
}

/**
 * The amount is the one thing the payer must not misread, so it gets the whole top of the
 * screen at display scale with the payee immediately beneath it — read as a single phrase,
 * "$ 592.68 to Rock Legends".
 */
@Composable
private fun AmountHero(summary: PaymentSummary) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = summary.amount,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        summary.payee?.let { payee ->
            Text(
                text = stringResource(R.string.tx_data_payee, payee),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Who is asking, as one quiet line rather than a card. The verifier matters for trust but
 * competes with the screen's subject for attention if it is given equal visual weight; the
 * trust state still escalates to a full notice card when it is not verified.
 *
 * Shared with the general consent screen so "who is asking" reads the same on both.
 */
@Composable
internal fun RequesterLine(
    verifierName: String,
    trusted: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (trusted) Icons.Filled.Verified else Icons.Filled.Warning,
            contentDescription = null,
            tint =
                if (trusted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.present_requested_by, verifierName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Hard trust failure, escalated to a full-width error card. Shared with the general consent
 * screen — an untrusted verifier is the same warning whatever is being asked for.
 *
 * The card itself lives in [TrustWarningCard] because the issuance flow raises the same alarm
 * about an untrusted *issuer*; this function is just the verifier-side copy bound to it.
 */
@Composable
internal fun UntrustedNotice() {
    TrustWarningCard(text = stringResource(R.string.present_unknown_verifier))
}

/**
 * Nothing in the wallet satisfies the request. Shared with the general consent screen.
 */
@Composable
internal fun NoMatchNotice() {
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
                text = stringResource(R.string.present_no_match),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * One page per DCQL candidate — the visible page is the selection, exactly as on the
 * general consent screen. Reuses [CandidateDots] so a candidate is still reachable without
 * a horizontal swipe.
 *
 * Unlike the general screen's card there are no claim rows to measure, so the page height
 * follows from the page width and the card's fixed aspect ratio rather than from a stack of
 * layout constants that drift out of sync with the card.
 */
@Composable
private fun PaymentCardCarousel(
    candidates: List<PresentationCandidate>,
    payingQueryIds: List<String>,
    credentialsById: Map<String, Credential>,
    pagerState: PagerState,
) {
    val peek = if (candidates.size > 1) 24.dp else 0.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The pager sits in a verticalScroll and so receives unbounded vertical
        // constraints — it needs an explicit height. Deriving that height from the width a
        // page actually gets (the peek narrows every page on both sides) is what holds the
        // card at the ID-1 ratio instead of letterboxing it inside a taller page.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth - peek * 2
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(pageWidth / CARD_ASPECT_RATIO),
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
                // A payment candidate shows one card: the paying instrument. Credentials the
                // verifier requested alongside it are not chosen between here — they are
                // listed below the carousel by [SupportingDisclosures].
                val paying =
                    splitAssignments(candidate, payingQueryIds)
                        .first
                        ?.let { credentialsById[it.credentialId] }
                Box(modifier = Modifier.semantics { contentDescription = position }) {
                    PaymentPassCard(credential = paying)
                }
            }
        }

        if (candidates.size > 1) {
            Text(
                text = stringResource(R.string.present_choose_payment_credential),
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
 * The credentials the verifier asked for alongside the payment — an age attestation, a
 * loyalty card — listed beneath the paying card.
 *
 * Names each credential and the attributes requested from it, but not their *values*. A payer
 * approving an amount needs to know they are also proving they are over 18; reading back the
 * date of birth they already know is the general consent screen's job, not this screen's. It
 * is the one place the payment screen departs from carrying no claim detail at all, because
 * silently disclosing a second credential is worse than a little extra text.
 */
@Composable
private fun SupportingDisclosures(
    matches: List<DcqlMatcher.Match>,
    credentialsById: Map<String, Credential>,
    locale: Locale,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.present_also_sharing),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                matches.forEachIndexed { index, match ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    SupportingDisclosureRow(
                        match = match,
                        credential = credentialsById[match.credentialId],
                        locale = locale,
                    )
                }
            }
        }
    }
}

/**
 * One supporting credential: its display name, then the requested attributes as issuer-supplied
 * labels on a single line. Falls back to the raw claim path when the credential carries no label
 * metadata, and to a truncated credential id when the credential row has not loaded — the same
 * degradation the general consent screen uses, so the two never disagree about a credential.
 */
@Composable
private fun SupportingDisclosureRow(
    match: DcqlMatcher.Match,
    credential: Credential?,
    locale: Locale,
) {
    val labels =
        remember(credential?.id, locale) {
            credential?.let { ClaimLabelResolver.resolve(it.displayMetadataJson, locale) }
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text =
                credential
                    ?.let { CredentialDisplay.resolve(it).name }
                    ?: match.credentialId.take(8),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                if (match.requestedClaimPaths.isEmpty()) {
                    // A query with no `claims` member asks for the whole credential rather
                    // than for named attributes, so there is nothing to enumerate.
                    stringResource(R.string.present_no_fields_requested)
                } else {
                    match.requestedClaimPaths.joinToString(", ") { path ->
                        labels?.labelFor(path) ?: path.joinToString(".")
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The paying instrument as a physical-feeling card: issuer gradient, issuer logo, the
 * credential's name, and the issuer-masked account so the payer can identify it. No claim
 * rows, no format name, no credential id.
 */
@Composable
private fun PaymentPassCard(credential: Credential?) {
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
        // The credential row hasn't loaded yet (or was deleted mid-flow). A neutral card
        // keeps the layout stable instead of collapsing the carousel.
        Box(modifier = baseModifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        return
    }

    val art = PassArt.forCredential(credential)
    val display = CredentialDisplay.resolve(credential)
    val maskedAccount = remember(credential.id) { maskedAccountOf(credential) }

    Box(modifier = baseModifier) {
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
                        onError = {
                            Log.w("PaymentPassCard", "logo load failed for $uri", it.result.throwable)
                        },
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
