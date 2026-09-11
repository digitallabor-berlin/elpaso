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
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.pick
import dev.digitallabor.elpaso.wallet.presentation.txdata.render.renderKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Issuer-driven consent block for a `transaction_data` entry, rendered from a
 * verified [TransactionDataTypeMetadata] per the PaSO View spec (paso-view.md §2).
 * Used whenever the matched credential carries a verified signed metadata JWT whose
 * `transaction_data_types` covers the verifier-supplied entry type.
 *
 * Rendering rules:
 * - `transaction_title` is the heading; falls back to a generic string when absent.
 * - `security_hint`, when present, MUST be displayed verbatim — never localized by
 *   the wallet (spec §3.2 of the metadata module). It is rendered as a SIBLING of the
 *   data card, not a row inside it: a hint qualifies the transaction rather than being
 *   one more field of it (see [SecurityHintBanner]).
 * - Each claim with a `display` array is rendered as `label: value` in CLAIMS-ARRAY
 *   order (not payload-key order) per spec §2.
 * - `value_type` and `display_type` are resolved by [ValueTypeFormatters]; the
 *   sealed `Formatted` result selects how the value is displayed (plain text, link,
 *   image, mini-markdown, or label-only).
 */
@Composable
fun DynamicTransactionDataBlock(
    item: TransactionData,
    metadata: TransactionDataTypeMetadata,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val payload = item.payloadScope
    // The hint is scoped to THIS transaction_data entry, so it stays inside this
    // composable (several entries may each carry their own) — but as a peer of the
    // data card rather than a nested one.
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
                // transaction_title is the SCREEN title per paso-proof-metadata.md §3.2 —
                // lifted to the app bar by PresentScreen. We deliberately don't repeat it
                // inside this card so the user doesn't see the same string twice.

                if (payload != null) {
                    metadata.claims
                        .filter { it.display.isNotEmpty() }
                        .forEach { claim ->
                            ClaimRow(
                                claim = claim,
                                payload = payload,
                                claimsArray = metadata.claims,
                                locale = locale,
                            )
                        }
                }
            }
        }

        metadata.uiLabels.securityHint.pick(locale)?.let { hint ->
            SecurityHintBanner(formatLabel(hint, metadata, payload, locale))
        }
    }
}

@Composable
private fun ClaimRow(
    claim: ClaimMetadata,
    payload: JsonObject,
    claimsArray: List<ClaimMetadata>,
    locale: Locale,
) {
    val display = claim.display.pick(locale) ?: return
    val rawLabel = display.name ?: claim.path.renderKey()
    // display_type formats the LABEL using the same rules as value_type — spec §3.
    val labelFormatted =
        ValueTypeFormatters.format(
            value = kotlinx.serialization.json.JsonPrimitive(rawLabel),
            valueType = display.displayType,
            locale = locale,
            resolveTemplate = { template, innerType ->
                resolveTemplate(template, innerType, claimsArray, payload, locale)
            },
        )
    // BRIDGE (temporary): `path` is now `List<String?>` with `null` meaning an array
    // wildcard, but this composable predates wildcard support and resolves object keys
    // only. Dropping the wildcards keeps existing non-wildcard metadata rendering exactly
    // as before. The whole composable is replaced by the pre-validated RenderPlan, which
    // is where wildcard expansion actually lands — this bridge dies with it.
    val rawValueElement = ValueTypeFormatters.resolvePath(payload, claim.path.filterNotNull())
    val valueFormatted =
        ValueTypeFormatters.format(
            value =
                if (claim.valueType?.startsWith(ValueTypeFormatters.TEMPLATE_PREFIX) == true) {
                    kotlinx.serialization.json.JsonPrimitive(
                        ValueTypeFormatters.asString(rawValueElement ?: kotlinx.serialization.json.JsonNull),
                    )
                } else {
                    rawValueElement
                },
            valueType = claim.valueType,
            locale = locale,
            resolveTemplate = { template, innerType ->
                resolveTemplate(template, innerType, claimsArray, payload, locale)
            },
            siblingLookup = { rel -> resolveSibling(payload, claim.path.filterNotNull(), rel) },
        )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FormattedAsText(
            formatted = labelFormatted,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        when (valueFormatted) {
            is ValueTypeFormatters.Formatted.LabelOnly -> {
                Unit
            }

            is ValueTypeFormatters.Formatted.Image -> {
                AsyncImage(
                    model = valueFormatted.uri,
                    contentDescription = rawLabel,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
            }

            is ValueTypeFormatters.Formatted.Url -> {
                val handler = LocalUriHandler.current
                Text(
                    text =
                        AnnotatedString(
                            text = valueFormatted.href,
                            spanStyle =
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                )
                // Tap target: anywhere on the URL text. Compose-Material3 doesn't have
                // a clickable Text overload natively; rely on enclosing card / use the
                // platform URL handler when the layer wires up a click.
                handler.toString() // suppress unused
            }

            is ValueTypeFormatters.Formatted.MiniMarkdown -> {
                Text(
                    text = renderMiniMarkdown(valueFormatted.text),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            is ValueTypeFormatters.Formatted.PlainText -> {
                Text(
                    text = valueFormatted.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Template placeholder resolver per paso-view.md §3 `template:${value_type}`.
 * `{<index>}` refers to the zero-based position of a claim in the type's `claims`
 * array. Substituted values are rendered with the referenced claim's own
 * `value_type`. The final string is formatted using the inner value_type
 * (passed back to the renderer via the returned [Formatted] kind).
 */
private fun resolveTemplate(
    template: String,
    innerType: String,
    claimsArray: List<ClaimMetadata>,
    payload: JsonObject,
    locale: Locale,
): ValueTypeFormatters.Formatted? {
    val PLACEHOLDER = Regex("""\{(\d+)\}""")
    var dropEntry = false
    val substituted =
        PLACEHOLDER.replace(template) { match ->
            if (dropEntry) return@replace ""
            val idx = match.groupValues[1].toInt()
            val target = claimsArray.getOrNull(idx) ?: return@replace match.value
            // BRIDGE (temporary): see the note at the ClaimRow call site. Wildcards are
            // dropped here too; index-bound wildcard references land with the real
            // TemplateInterpolator.
            val targetValue = ValueTypeFormatters.resolvePath(payload, target.path.filterNotNull())
            if (targetValue == null) {
                dropEntry = true
                return@replace ""
            }
            when (val formatted = ValueTypeFormatters.format(targetValue, target.valueType, locale)) {
                is ValueTypeFormatters.Formatted.PlainText -> formatted.text
                is ValueTypeFormatters.Formatted.MiniMarkdown -> formatted.text
                is ValueTypeFormatters.Formatted.Url -> formatted.href
                is ValueTypeFormatters.Formatted.Image -> formatted.uri
                is ValueTypeFormatters.Formatted.LabelOnly -> ""
            }
        }
    if (dropEntry) return null
    // Apply the inner value_type to the now-interpolated string.
    return when (innerType) {
        ValueTypeFormatters.MINI_MARKDOWN -> ValueTypeFormatters.Formatted.MiniMarkdown(substituted)
        ValueTypeFormatters.URL -> ValueTypeFormatters.Formatted.Url(substituted)
        else -> ValueTypeFormatters.Formatted.PlainText(substituted)
    }
}

/**
 * Look up `<path-last-segment><relativeKey>` in the same parent object as the
 * claim's leaf — used for `image` value_type to find a sibling `*#integrity` claim
 * per paso-view.md §3.
 */
private fun resolveSibling(
    payload: JsonObject,
    path: List<String>,
    relativeKey: String,
): JsonElement? {
    if (path.isEmpty()) return null
    val parent =
        if (path.size == 1) {
            payload
        } else {
            ValueTypeFormatters.resolvePath(payload, path.dropLast(1)) as? JsonObject ?: return null
        }
    val leafKey = path.last() + relativeKey
    return parent[leafKey]
}

@Composable
private fun FormattedAsText(
    formatted: ValueTypeFormatters.Formatted,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    when (formatted) {
        is ValueTypeFormatters.Formatted.MiniMarkdown -> {
            Text(text = renderMiniMarkdown(formatted.text), style = style, color = color)
        }

        is ValueTypeFormatters.Formatted.PlainText -> {
            Text(text = formatted.text, style = style, color = color)
        }

        is ValueTypeFormatters.Formatted.Url -> {
            Text(text = formatted.href, style = style, color = color)
        }

        is ValueTypeFormatters.Formatted.LabelOnly,
        is ValueTypeFormatters.Formatted.Image,
        -> {
            Unit
        }
    }
}

/**
 * Minimal CommonMark subset for `mini_markdown` per paso-view.md §3: `*em*`/`_em_`,
 * `**strong**`/`__strong__`, and `<u>underline</u>`. Anything else stays literal —
 * the spec mandates this exact subset.
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
 * Issuer-supplied security hint — rendered verbatim per the metadata spec §3.2
 * ("the Wallet SHALL display it exactly as provided and SHALL NOT alter or remove
 * it"). The string never passes through `stringResource`. The label's own
 * `value_type` is applied per §3.2 — typically plain text but mini_markdown is
 * permitted so emphasis stays meaningful.
 *
 * Presented as a caution, deliberately NOT as an error: this hint accompanies every
 * legitimate transaction of its type, so reusing `errorContainer` here would dilute
 * the one red signal on this screen that means stop — `UntrustedNotice`'s hard trust
 * failure. Geometry matches that notice (20dp corners, 24dp leading icon, 18/16dp
 * padding) so the screen's warnings still read as one family; only the severity tier
 * differs.
 *
 * No `fontWeight` is imposed on the text: the hint may be `mini_markdown`, and a
 * blanket SemiBold would flatten the issuer's own `**strong**` emphasis.
 */
@Composable
private fun SecurityHintBanner(hint: ValueTypeFormatters.Formatted) {
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
                // Announced before the verbatim hint so TalkBack conveys the ROLE of
                // the text. Labelling the icon is added context, not an alteration of
                // the issuer's string, so §3.2 is untouched.
                contentDescription = stringResource(R.string.present_security_hint),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            FormattedLabel(
                formatted = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Resolves a [LocalizedLabel] to a [ValueTypeFormatters.Formatted] applying the
 * label's own `value_type` per metadata spec §3.2. Templates have access to the
 * surrounding `transaction_data_types` entry's `claims` array and the verifier's
 * payload, matching the semantics defined in paso-view.md §3.
 */
private fun formatLabel(
    label: LocalizedLabel,
    metadata: TransactionDataTypeMetadata,
    payload: JsonObject?,
    locale: Locale,
): ValueTypeFormatters.Formatted =
    ValueTypeFormatters.format(
        value = kotlinx.serialization.json.JsonPrimitive(label.value),
        valueType = label.valueType,
        locale = locale,
        resolveTemplate = { template, innerType ->
            if (payload == null) {
                ValueTypeFormatters.Formatted.PlainText(template)
            } else {
                resolveTemplate(template, innerType, metadata.claims, payload, locale)
            }
        },
    )

/**
 * Renders a [ValueTypeFormatters.Formatted] label with shared styling. Image/url
 * inside a label is unusual but spec-permitted; we render them as text so the
 * surrounding container (heading/banner/button) keeps its semantics.
 */
@Composable
private fun FormattedLabel(
    formatted: ValueTypeFormatters.Formatted,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    when (formatted) {
        is ValueTypeFormatters.Formatted.MiniMarkdown -> {
            Text(
                text = renderMiniMarkdown(formatted.text),
                style = style,
                color = color,
                fontWeight = fontWeight,
                modifier = modifier,
            )
        }

        is ValueTypeFormatters.Formatted.PlainText -> {
            Text(
                text = formatted.text,
                style = style,
                color = color,
                fontWeight = fontWeight,
                modifier = modifier,
            )
        }

        is ValueTypeFormatters.Formatted.Url -> {
            Text(
                text = formatted.href,
                style = style,
                color = color,
                fontWeight = fontWeight,
                modifier = modifier,
            )
        }

        is ValueTypeFormatters.Formatted.Image,
        is ValueTypeFormatters.Formatted.LabelOnly,
        -> {
            Unit
        }
    }
}

/**
 * Public entry for callers outside this file (e.g. PresentScreen button labels)
 * that need to honor a LocalizedLabel's value_type for non-claim UI elements.
 */
@Stable
object UiLabelRenderer {
    fun resolve(
        label: LocalizedLabel,
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject?,
        locale: Locale,
    ): ValueTypeFormatters.Formatted = formatLabel(label, metadata, payload, locale)
}
