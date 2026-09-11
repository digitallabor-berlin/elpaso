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
     * @param referencingWildcards how many array wildcards the *referencing* claim's path
     *   carries. §3 lets a placeholder reference only claims with the same number or fewer,
     *   because each wildcard in the referenced path binds to the corresponding index in
     *   the referencing one — a deeper reference has an index with nothing to bind to.
     */
    fun interpolate(
        template: String,
        claims: List<ClaimMetadata>,
        payload: JsonObject,
        locale: Locale,
        referencingWildcards: Int = 0,
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
                        target.path.wildcardCount() <= referencingWildcards
                if (!referenceable) {
                    failure = Outcome.Incompatible(IncompatibilityReason.Code.TEMPLATE_BAD_REFERENCE)
                    return@replace ""
                }

                val value = resolve(payload, target.path.filterNotNull())
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

    private fun resolve(
        root: JsonObject,
        path: List<String>,
    ): JsonElement? {
        var node: JsonElement = root
        for (segment in path) {
            val obj = node as? JsonObject ?: return null
            node = obj[segment] ?: return null
        }
        return node
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{(\d+)\}""")
    }
}
