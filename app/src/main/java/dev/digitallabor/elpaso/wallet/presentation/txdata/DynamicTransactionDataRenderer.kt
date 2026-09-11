package dev.digitallabor.elpaso.wallet.presentation.txdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.svg.SvgDecoder
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.FormattedText
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.ImageSource
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderPlan
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderRow
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderedLabel
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.RenderedValue

/**
 * Issuer-driven consent block for a `transaction_data` entry.
 *
 * **This composable makes no compatibility decisions.** It takes a [RenderPlan], which is
 * by construction already conformant: every label is within its length cap and free of
 * prohibited characters, every value matched its declared `value_type`, and one locale
 * matched across every display array. PaSO Core §7.4.2 settles all of that during entry
 * selection, before the screen exists — an entry that failed never reaches here, it
 * reaches the refusal screen instead.
 *
 * That division is why the old in-composable formatting is gone. A renderer that could
 * still discover a problem mid-draw has only two options, both forbidden by PaSO View §2:
 * degrade the content, or draw a broken screen.
 *
 * Layout notes that survived the rewrite:
 * - `transaction_title` is lifted to the app bar by the screen, so it is deliberately not
 *   repeated inside the card.
 * - The security hint is a *sibling* of the data card, not a row inside it: a hint
 *   qualifies the whole transaction rather than being one more field of it.
 */
@Composable
fun DynamicTransactionDataBlock(
    plan: RenderPlan,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val imageLoader = rememberVerifiedBytesImageLoader()
                plan.rows.forEach { row -> ClaimRow(row, imageLoader) }
            }
        }

        plan.securityHint?.let { SecurityHintBanner(it) }
    }
}

/**
 * A loader that can decode an image but cannot fetch one.
 *
 * Deliberately **not** the app's singleton loader. That one is built in `ElPasoApp` with
 * `KtorNetworkFetcherFactory(httpClient)` over the shared client — the one that logs full
 * URLs, headers and bodies — because it exists to load issuer logos. Pointing it at
 * `transaction_data` content would hand a verifier-named host the wallet's general-purpose
 * HTTP stack.
 *
 * Registering no network component at all is what makes PaSO View §3's "external resources
 * SHALL NOT be loaded" a property of the wiring rather than of the argument that happens to
 * be passed. Every image reaching here is already [ImageSource.Inline] — bytes fetched and
 * hash-verified by `ImageResolver`, or decoded from a data URL — so the only fetch this
 * loader could perform would be one nobody verified. `SvgDecoder` (AndroidSVG) renders a
 * single static frame, which is the rest of §3's SVG rule: no script, no animation, no
 * interactivity.
 */
@Composable
private fun rememberVerifiedBytesImageLoader(): ImageLoader {
    val context = LocalPlatformContext.current
    return remember(context) {
        ImageLoader
            .Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}

/**
 * One labelled value, in the order the issuer's `claims` array declared it (PaSO View §2
 * requires claims-array order, not payload-key order — the payload is the verifier's to
 * arrange).
 */
@Composable
private fun ClaimRow(
    row: RenderRow,
    imageLoader: ImageLoader,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        row.label?.let { label ->
            LabelText(
                label = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }

        when (val value = row.value) {
            // The label carries the whole meaning; there is deliberately nothing to draw.
            is RenderedValue.LabelOnly -> {
                Unit
            }

            is RenderedValue.Image -> {
                when (val source = value.source) {
                    is ImageSource.Inline -> {
                        // The model is a ByteArray, never a URL. Combined with a loader that
                        // has no network component, there is no path by which drawing this
                        // row performs I/O. The bounded height is §3's "an image SHALL NOT
                        // overlay or displace other transaction data or Wallet controls" —
                        // images are informational, so they may not crowd out the fields the
                        // user is actually consenting to.
                        AsyncImage(
                            model = source.bytes,
                            imageLoader = imageLoader,
                            contentDescription = row.label?.plainText(),
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                        )
                    }

                    // Unreachable by construction. TransactionDataCompatibilityChecker runs
                    // §7.4.2 step 3 before any plan escapes it, so every image in a plan is
                    // Inline; a fetch that failed made the whole entry incompatible and this
                    // composable was never called. The branch exists only because RenderPlan
                    // also models the pre-resolution state the checker works on internally,
                    // and `TransactionDataCompatibilityCheckerTest` is what holds the
                    // invariant. Drawing nothing is the right residual behaviour: the
                    // alternative is showing bytes nobody verified.
                    is ImageSource.Remote -> {
                        Unit
                    }
                }
            }

            is RenderedValue.Link -> {
                Text(
                    text =
                        AnnotatedString(
                            text = value.display,
                            spanStyle =
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }

            is RenderedValue.Text -> {
                Text(
                    text = value.content.annotated(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }
    }
}

/**
 * Renders a validated label. Exposed because the screen's action buttons and app-bar title
 * come from the same `ui_labels` block and must honour the same formatting.
 *
 * **Wrap, never shorten.** PaSO View §2: "The Wallet MUST NOT truncate, elide, or otherwise
 * shorten a label; it MAY wrap a label across multiple lines provided the text remains
 * visible in full", and §2 again: it "MUST NOT rely on platform default controls that
 * truncate or ellipsize overflowing text." Compose's defaults happen to satisfy this, which
 * is exactly why the three parameters are written out: the requirement is then visible at
 * the one place a future edit would break it, instead of being an accident of the toolkit.
 * §5.2 explains the stake — "attacker-influenced text truncates to attacker-chosen
 * prefixes", so a label cut after "Confirm payment of €1.00" hides its own continuation.
 */
@Composable
fun LabelText(
    label: RenderedLabel,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = label.content.annotated(),
        style = style,
        color = color,
        fontWeight = fontWeight,
        softWrap = true,
        overflow = TextOverflow.Clip,
        maxLines = Int.MAX_VALUE,
        modifier = modifier,
    )
}

/** The label's text with no formatting — for content descriptions and plain-string sinks. */
fun RenderedLabel.plainText(): String =
    when (val c = content) {
        is FormattedText.Plain -> c.text
        is FormattedText.Markdown -> c.source
    }

@Composable
private fun FormattedText.annotated(): AnnotatedString =
    when (this) {
        is FormattedText.Plain -> AnnotatedString(text)
        is FormattedText.Markdown -> renderMiniMarkdown(source)
    }

/**
 * The `mini_markdown` subset of CommonMark permitted by PaSO View §3: emphasis, strong
 * emphasis, and `<u>` for underline. Everything else — every other CommonMark construct
 * and all raw HTML — renders as its literal string representation, which is the spec's
 * requirement and also the whole security property: issuer text is display data, never
 * markup the wallet will act on.
 */
internal fun renderMiniMarkdown(input: String): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                // strong: ** or __
                (ch == '*' || ch == '_') && i + 1 < input.length && input[i + 1] == ch -> {
                    val close = input.indexOf("$ch$ch", i + 2)
                    if (close > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(input.substring(i + 2, close))
                        }
                        i = close + 2
                        continue
                    } else {
                        append(ch)
                    }
                }

                // emphasis: * or _
                ch == '*' || ch == '_' -> {
                    val close = input.indexOf(ch, i + 1)
                    if (close > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(input.substring(i + 1, close))
                        }
                        i = close + 1
                        continue
                    } else {
                        append(ch)
                    }
                }

                ch == '<' && input.regionMatches(i, "<u>", 0, 3, ignoreCase = true) -> {
                    val close = input.indexOf("</u>", i + 3, ignoreCase = true)
                    if (close > 0) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                            append(input.substring(i + 3, close))
                        }
                        i = close + 4
                        continue
                    } else {
                        append(ch)
                    }
                }

                else -> {
                    append(ch)
                }
            }
            i++
        }
    }

/**
 * The issuer's security hint, rendered verbatim (paso-proof-metadata.md §3.2: "the Wallet
 * SHALL display it exactly as provided and SHALL NOT alter or remove it"). The string
 * never passes through `stringResource`, and it is plain text by construction — §3.3
 * forbids a `security_hint` entry from carrying a `value_type` at all.
 *
 * Presented as a caution, deliberately not as an error: this hint accompanies every
 * legitimate transaction of its type, so reusing `errorContainer` would dilute the one red
 * signal on this screen that means stop — `UntrustedNotice`'s hard trust failure. Geometry
 * matches that notice (20dp corners, 24dp leading icon, 18/16dp padding) so the warnings
 * still read as one family; only the severity tier differs.
 */
@Composable
internal fun SecurityHintBanner(hint: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.GppMaybe,
                // Announced before the verbatim hint so TalkBack conveys the ROLE of the
                // text. Labelling the icon is added context, not an alteration of the
                // issuer's string, so §3.2 is untouched.
                contentDescription = stringResource(R.string.present_security_hint),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                softWrap = true,
                overflow = TextOverflow.Clip,
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}
