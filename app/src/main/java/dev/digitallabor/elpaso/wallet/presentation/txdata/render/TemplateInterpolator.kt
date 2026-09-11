package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.presentation.txdata.ValueTypeFormatters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Placeholder interpolation for the `template:${value_type}` composable prefix
 * (paso-view.md §3).
 *
 * A template is a string carrying `{<index>}` placeholders, where the index is a position
 * in the type's `claims` array. Every restriction below exists to keep that mechanism from
 * becoming an injection channel — §5.1 treats metadata and payload strictly as display
 * data, never as instructions:
 *
 * - **Single pass.** A resolved value containing `{1}` stays the literal text `{1}`.
 *   Re-scanning would let a verifier-supplied payload value reach a claim the issuer's
 *   template never named.
 * - **Restricted references.** `image`, `label_only` and nested `template:` claims cannot
 *   be referenced. The first would inline a URI as prose; the second resolves to nothing;
 *   the third reopens the single-pass hole from the other side.
 * - **Absence discards the locale entry**, rather than substituting an empty string. A
 *   sentence with a silently missing amount is worse than no sentence: the wallet falls
 *   back to the next locale, and if none survives the entry is not compatible.
 *
 * Pure and JVM-testable, like the validator that calls it.
 */
class TemplateInterpolator {
    sealed interface Outcome {
        data class Ok(
            val text: String,
        ) : Outcome

        /**
         * A referenced claim is absent from the payload. §3 step 2 discards *this locale
         * entry* — the caller tries the next locale before declaring the entry unusable.
         */
        data object DiscardLocaleEntry : Outcome

        /** The template breaks a rule no locale fallback can repair. */
        data class Incompatible(
            val code: IncompatibilityReason.Code,
        ) : Outcome
    }

    /**
     * @param template the raw template text, before substitution
     * @param claims the transaction data type's full `claims` array — placeholder indices
     *   are positions in it
     * @param payload the verifier-supplied `transaction_data` `payload`
     * @param boundIndices the array indices the *referencing* claim instance resolved its
     *   own wildcards to, outermost first. §3: "Placeholders MUST only reference claims
     *   whose `path` contains the same number or fewer `null` entries than the referencing
     *   claim's `path`; each `null` in the referenced claim's `path` is resolved to the
     *   same array index as the corresponding `null` in the referencing claim's `path`."
     *   So this list is both the permission check (its size is the ceiling on a reference's
     *   wildcard count) and the binding itself. A claim with no wildcards passes an empty
     *   list, which permits only wildcard-free references — the previous behaviour.
     */
    fun interpolate(
        template: String,
        claims: List<ClaimMetadata>,
        payload: JsonObject,
        locale: Locale,
        boundIndices: List<Int> = emptyList(),
    ): Outcome {
        var failure: Outcome? = null

        // Regex.replace walks the input once and never revisits what it substituted, which
        // is exactly the single-pass guarantee §3 requires. Do not "simplify" this into a
        // loop that re-scans the accumulated result.
        val substituted =
            PLACEHOLDER.replace(template) { match ->
                if (failure != null) return@replace ""

                val index = match.groupValues[1].toIntOrNull()
                val target =
                    index?.let(claims::getOrNull)
                        ?: return@replace match.value // out of bounds: literal text, per §3 step 1

                val targetType = target.valueType
                val referenceable =
                    targetType != ValueTypeFormatters.IMAGE &&
                        targetType != ValueTypeFormatters.LABEL_ONLY &&
                        targetType?.startsWith(ValueTypeFormatters.TEMPLATE_PREFIX) != true &&
                        target.path.wildcardCount() <= boundIndices.size
                if (!referenceable) {
                    failure = Outcome.Incompatible(IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE)
                    return@replace ""
                }

                val value = resolveValue(payload, bind(target.path, boundIndices))
                if (value == null) {
                    failure = Outcome.DiscardLocaleEntry
                    return@replace ""
                }

                if (targetType == null && !(value is JsonPrimitive && value.isString)) {
                    failure = Outcome.Incompatible(IncompatibilityReason.Code.TEMPLATE_NON_STRING)
                    return@replace ""
                }

                format(value, targetType, locale)
            }

        return failure ?: Outcome.Ok(substituted)
    }

    /** §3 step 3: the substituted text is the referenced claim's value under its own `value_type`. */
    private fun format(
        value: JsonElement,
        valueType: String?,
        locale: Locale,
    ): String =
        when (val formatted = ValueTypeFormatters.format(value, valueType, locale)) {
            is ValueTypeFormatters.Formatted.PlainText -> formatted.text

            is ValueTypeFormatters.Formatted.MiniMarkdown -> formatted.text

            is ValueTypeFormatters.Formatted.Url -> formatted.href

            // Unreachable: both types are rejected as references above.
            is ValueTypeFormatters.Formatted.Image -> formatted.uri

            is ValueTypeFormatters.Formatted.LabelOnly -> ""
        }

    /**
     * Binds a referenced claim's wildcards to the referencing instance's indices,
     * positionally: the referenced path's first `null` takes the referencing path's first
     * index, and so on.
     *
     * That positional rule is what makes `{0}` inside `items[1].label` resolve to
     * `items[1].name` rather than `items[0].name`. Getting it wrong is not a crash — it
     * quietly attributes one line item's text to another, which on a payment screen means
     * the user reads a description that belongs to a different amount.
     *
     * Indexing [boundIndices] is safe because the caller has already refused any reference
     * whose wildcard count exceeds its size.
     */
    private fun bind(
        path: List<String?>,
        boundIndices: List<Int>,
    ): ResolvedPath {
        var nullsSeen = 0
        return path.map { segment ->
            if (segment == null) PathStep.Index(boundIndices[nullsSeen++]) else PathStep.Key(segment)
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{(\d+)\}""")
    }
}
