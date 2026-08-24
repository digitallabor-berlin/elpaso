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
import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import dev.digitallabor.elpaso.wallet.presentation.txdata.ValueTypeFormatters
import dev.digitallabor.elpaso.wallet.presentation.txdata.formatIsoCurrencyAmount
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a payment consent screen needs to show, distilled from a `transaction_data` entry:
 * an amount and who is being paid. Everything else the entry carries — payee identifiers,
 * transaction references, hash algorithms — is protocol bookkeeping the payer cannot act
 * on, so it is deliberately not part of this model.
 */
internal data class PaymentSummary(
    val amount: String,
    val payee: String?,
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
 * ID-1 aspect ratio (ISO/IEC 7810 — 85.60 × 53.98 mm). The payment card is a stand-in for a
 * physical card, so it carries the physical proportions rather than an arbitrary height.
 */
private const val CARD_ASPECT_RATIO = 1.586f

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
                PaymentSummary(amount = entry.amountDisplay, payee = entry.payeeName)
            }

            is TransactionData.PasoPayment -> {
                PaymentSummary(
                    amount = formatIsoCurrencyAmount(entry.amount, entry.currency, entry.amountRaw),
                    payee = entry.payeeName,
                )
            }

            is TransactionData.PaymentData -> {
                val amount =
                    listOfNotNull(entry.currency, entry.amount)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() }
                amount?.let { PaymentSummary(amount = it, payee = entry.payeeName) }
            }

            else -> {
                null
            }
        }
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
    authorizeLabel: ValueTypeFormatters.Formatted?,
    authorizeFallback: String,
    denialLabel: ValueTypeFormatters.Formatted?,
    onAuthorize: () -> Unit,
    onCancel: () -> Unit,
) {
    val scroll = rememberScrollState()
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

        if (!trusted) {
            UntrustedNotice()
        }

        if (candidates.isEmpty()) {
            NoPaymentCredentialNotice()
        } else {
            PaymentCardCarousel(
                candidates = candidates,
                credentialsById = credentialsById,
                pagerState = pagerState,
            )
        }

        ActionRow(
            authorizeLabel = authorizeLabel,
            authorizeFallback = authorizeFallback,
            denialLabel = denialLabel,
            authorizeEnabled = candidates.isNotEmpty() && trusted,
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
 * competes with the amount for attention if it is given equal visual weight; the trust
 * state still escalates to [UntrustedNotice] when it is not verified.
 */
@Composable
private fun RequesterLine(
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
            text = stringResource(R.string.present_payment_requested_by, verifierName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UntrustedNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.present_unknown_verifier),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun NoPaymentCredentialNotice() {
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
                // A payment candidate is one card. If a request ever assigns several
                // credentials to one candidate, the first is the paying instrument and the
                // rest are supporting disclosures the payer does not choose between here.
                val paying =
                    candidate.assignments.firstNotNullOfOrNull { credentialsById[it.credentialId] }
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
