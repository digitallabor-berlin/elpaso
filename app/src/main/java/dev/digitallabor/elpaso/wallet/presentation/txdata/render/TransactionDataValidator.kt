package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decides whether one `transaction_data` entry is compatible with the credential whose
 * metadata describes it — PaSO Core §7.4.2 step 2 — and, when it is, produces the
 * [RenderPlan] the consent screen draws.
 *
 * **Pure and synchronous by design.** Every rule here is decidable from the metadata and
 * the payload alone, which keeps the whole thing unit-testable on the JVM. That matters
 * concretely: the module sets `testOptions.unitTests.isReturnDefaultValues = true`, so
 * anything reaching for `android.*` would silently return null/0/false and the tests would
 * assert nothing. Resolving remote images is the one step that needs I/O, and it is
 * deliberately *not* here — §7.4.2 makes it step 3, after this verdict.
 *
 * **There is no permissive path.** The renderer this replaces fell back to plain text for
 * anything it did not understand. PaSO View §2 and §3 require the opposite: a violated
 * constraint makes the entry *not compatible*, full stop. That is not gated behind
 * developer mode, for the same reason the issuance signature gate is not — a consent
 * screen that quietly degrades is worse than one that refuses.
 */
class TransactionDataValidator(
    private val graphemes: GraphemeCounter = GraphemeCounter.Default,
    private val maxRenderedItems: Int = RenderLimits.MAX_RENDERED_ITEMS,
) {
    /**
     * @param metadata the issuer-signed `transaction_data_types` entry for [payload]'s type
     * @param payload the verifier-supplied `transaction_data` `payload` object
     * @param selection the locale chosen for this transaction
     */
    fun validate(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        selection: LocaleSelection,
    ): ValidationResult {
        structuralViolation(metadata)?.let { return it.asResult() }
        payloadViolation(metadata, payload)?.let { return it.asResult() }
        return buildPlan(metadata, payload, selection)
    }

    // --- Plan construction ---

    private fun buildPlan(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        selection: LocaleSelection,
    ): ValidationResult {
        val ui = metadata.uiLabels

        val title =
            uiLabel(ui.transactionTitle, RenderLimits.TRANSACTION_TITLE_MAX, "transaction_title", selection)
                .onBad { return it }
        val affirmative =
            uiLabel(ui.affirmativeActionLabel, RenderLimits.AFFIRMATIVE_LABEL_MAX, "affirmative_action_label", selection)
                .onBad { return it }
        val denial =
            uiLabel(ui.denialActionLabel, RenderLimits.DENIAL_LABEL_MAX, "denial_action_label", selection)
                .onBad { return it }

        // The hint is the one label the wallet may never reformat: §3.2 requires it be
        // displayed exactly as provided, and §3.3 forbids it carrying a `value_type` at
        // all. It is therefore a plain String in the plan, not a RenderedLabel — there is
        // no formatting decision left to represent.
        val hintEntry = pickLabel(ui.securityHint, selection)
        if (hintEntry != null && hintEntry.valueType != null) {
            return reason(
                IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
                "security_hint must not carry a value_type (found '${hintEntry.valueType}')",
            ).asResult()
        }
        hintEntry?.let { entry ->
            characterViolation(entry.value, "security_hint")?.let { return it.asResult() }
            lengthViolation(entry.value, RenderLimits.SECURITY_HINT_MAX, "security_hint")?.let { return it.asResult() }
        }

        val rows = mutableListOf<RenderRow>()
        for (claim in metadata.claims) {
            // §3.1: a claim with no `display` array is an internal value "irrelevant to the
            // user's consent", so it is not a rendered item and never reaches the screen.
            if (claim.display.isEmpty()) continue
            val display = pickDisplay(claim.display, selection) ?: continue

            val where = "claim '${claim.path.renderKey()}'"
            val label =
                display.name?.let { name ->
                    labelFrom(name, display.displayType, RenderLimits.CLAIM_NAME_MAX, where)
                        .onBad { return it }
                }

            // Value formatting and conformance are not this task's concern; the raw value
            // is carried through so the row exists, and the strict value-type pass replaces it.
            val raw = resolve(payload, claim.path.filterNotNull())
            rows += RenderRow(label = label, value = RenderedValue.Text(FormattedText.Plain(rawText(raw))))
        }

        val uiElementCount = listOfNotNull(title, affirmative, denial).size + if (hintEntry != null) 1 else 0

        return ValidationResult.Compatible(
            RenderPlan(
                title = title,
                rows = rows,
                securityHint = hintEntry?.value,
                affirmativeLabel = affirmative,
                denialLabel = denial,
                selectedLocaleTag = selection.localeTag,
                totalItemCount = rows.size + uiElementCount,
            ),
        )
    }

    // --- Label constraints (paso-proof-metadata.md §3.3) ---

    /** Either a validated label, or the reason it is not one. */
    private sealed interface LabelOutcome {
        data class Ok(val label: RenderedLabel?) : LabelOutcome

        data class Bad(val reason: IncompatibilityReason) : LabelOutcome
    }

    /**
     * Unwraps a [LabelOutcome], handing a failure to [bail] — which callers use to return
     * out of the enclosing function. Keeps the happy path free of `when` noise without
     * losing the failure.
     */
    private inline fun LabelOutcome.onBad(bail: (ValidationResult) -> Nothing): RenderedLabel? =
        when (this) {
            is LabelOutcome.Ok -> label
            is LabelOutcome.Bad -> bail(ValidationResult.Incompatible(reason))
        }

    private fun uiLabel(
        entries: List<LocalizedLabel>,
        max: Int,
        where: String,
        selection: LocaleSelection,
    ): LabelOutcome {
        val entry = pickLabel(entries, selection) ?: return LabelOutcome.Ok(null)
        return labelFrom(entry.value, entry.valueType, max, where)
    }

    /**
     * Applies §3.3 to one label: the formatting type must be text-producing, the text must
     * avoid the prohibited characters, and it must fit its cap in grapheme clusters.
     *
     * Order matters for diagnosis, not for the verdict — an unsupported type is reported
     * as such even if the text would also have been too long.
     */
    private fun labelFrom(
        text: String,
        type: String?,
        max: Int,
        where: String,
    ): LabelOutcome {
        if (type != null && type !in ALLOWED_LABEL_TYPES) {
            return LabelOutcome.Bad(
                reason(
                    IncompatibilityReason.Code.LABEL_UNSUPPORTED_TYPE,
                    "$where uses label type '$type'; labels must be plain, mini_markdown, or template:mini_markdown",
                ),
            )
        }
        characterViolation(text, where)?.let { return LabelOutcome.Bad(it) }
        lengthViolation(text, max, where)?.let { return LabelOutcome.Bad(it) }

        val content =
            if (type == MINI_MARKDOWN || type == TEMPLATE_MINI_MARKDOWN) {
                FormattedText.Markdown(text)
            } else {
                FormattedText.Plain(text)
            }
        return LabelOutcome.Ok(RenderedLabel(content))
    }

    private fun characterViolation(
        text: String,
        where: String,
    ): IncompatibilityReason? =
        when {
            LabelText.hasControlChar(text) ->
                reason(IncompatibilityReason.Code.LABEL_CONTROL_CHAR, "$where contains a control character")
            LabelText.hasDirectionalOverride(text) ->
                reason(
                    IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
                    "$where contains a directional embedding or override character",
                )
            !LabelText.hasBalancedIsolates(text) ->
                reason(
                    IncompatibilityReason.Code.LABEL_DIRECTIONAL_OVERRIDE,
                    "$where contains an unterminated directional isolate",
                )
            else -> null
        }

    private fun lengthViolation(
        text: String,
        max: Int,
        where: String,
    ): IncompatibilityReason? {
        val clusters = graphemes.count(text)
        return if (clusters > max) {
            reason(
                IncompatibilityReason.Code.LABEL_TOO_LONG,
                "$where is $clusters grapheme clusters, over the $max cap",
            )
        } else {
            null
        }
    }

    // --- Locale matching (interim; PaSO View §4 replaces this wholesale) ---

    private fun pickDisplay(
        entries: List<ClaimDisplay>,
        selection: LocaleSelection,
    ): ClaimDisplay? = pickBy(entries, selection) { it.locale }

    private fun pickLabel(
        entries: List<LocalizedLabel>,
        selection: LocaleSelection,
    ): LocalizedLabel? = pickBy(entries, selection) { it.locale }

    /**
     * Exact tag, then language, then the entry without a locale.
     *
     * Deliberately returns null rather than falling back to the first entry: §4 makes "no
     * match" a real outcome that excludes the credential, and a first-entry fallback would
     * silently render one locale's label inside another locale's screen.
     */
    private fun <T> pickBy(
        entries: List<T>,
        selection: LocaleSelection,
        tagOf: (T) -> String?,
    ): T? {
        if (entries.isEmpty()) return null
        val tag = selection.locale.toLanguageTag()
        val language = selection.locale.language
        return entries.firstOrNull { tagOf(it).equals(tag, ignoreCase = true) }
            ?: entries.firstOrNull { tagOf(it)?.substringBefore('-').equals(language, ignoreCase = true) }
            ?: entries.firstOrNull { tagOf(it).isNullOrBlank() }
    }

    private fun rawText(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull.orEmpty()

    // --- Structural constraints (paso-proof-metadata.md §3.3) ---

    /**
     * A type whose metadata violates §3.3 is "not supported by the credential" — the spec
     * says the wallet treats it that way *independent of rendering*, which is why these
     * checks run before anything touches the payload.
     */
    private fun structuralViolation(metadata: TransactionDataTypeMetadata): IncompatibilityReason? {
        if (metadata.claims.size > RenderLimits.MAX_CLAIMS) {
            return reason(
                IncompatibilityReason.Code.TOO_MANY_CLAIMS,
                "${metadata.claims.size} claims exceeds ${RenderLimits.MAX_CLAIMS}",
            )
        }

        val seenPaths = mutableSetOf<String>()
        for (claim in metadata.claims) {
            val key = claim.path.renderKey()
            if (!seenPaths.add(key)) {
                return reason(IncompatibilityReason.Code.DUPLICATE_CLAIM_PATH, "duplicate claim path '$key'")
            }
        }

        // Each display array and each populated ui_labels array is checked independently:
        // §3.3 scopes "no two entries with the same locale" to a single array, so two
        // different claims may of course both carry an "en" entry.
        for (claim in metadata.claims) {
            localeViolation(claim.display.map { it.locale }, "claim '${claim.path.renderKey()}'")?.let { return it }
        }
        val ui = metadata.uiLabels
        localeViolation(ui.transactionTitle.map { it.locale }, "transaction_title")?.let { return it }
        localeViolation(ui.affirmativeActionLabel.map { it.locale }, "affirmative_action_label")?.let { return it }
        localeViolation(ui.denialActionLabel.map { it.locale }, "denial_action_label")?.let { return it }
        localeViolation(ui.securityHint.map { it.locale }, "security_hint")?.let { return it }

        return null
    }

    /**
     * §3.3: within one array, no two entries share a `locale`, and at most one entry omits
     * it. Tags are compared case-insensitively — BCP-47 is case-insensitive, so `en` and
     * `EN` are the same locale and must not both appear.
     */
    private fun localeViolation(
        locales: List<String?>,
        where: String,
    ): IncompatibilityReason? {
        val seen = mutableSetOf<String>()
        var defaults = 0
        for (locale in locales) {
            if (locale.isNullOrBlank()) {
                defaults++
                if (defaults > 1) {
                    return reason(
                        IncompatibilityReason.Code.MULTIPLE_DEFAULT_LOCALE,
                        "$where has more than one entry without a locale",
                    )
                }
            } else if (!seen.add(locale.lowercase())) {
                return reason(IncompatibilityReason.Code.DUPLICATE_LOCALE, "$where repeats locale '$locale'")
            }
        }
        return null
    }

    // --- Payload conformance (paso-core.md §7.4.2 step 2) ---

    private fun payloadViolation(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
    ): IncompatibilityReason? {
        coverageViolation(metadata.claims, payload)?.let { return it }

        for (claim in metadata.claims) {
            if (claim.mandatory && resolve(payload, claim.path.filterNotNull()) == null) {
                return reason(
                    IncompatibilityReason.Code.MISSING_REQUIRED_FIELD,
                    "required claim '${claim.path.renderKey()}' is absent from the payload",
                )
            }
        }
        return null
    }

    /**
     * §7.4.2 step 2: the payload "does not contain fields not covered by a claim `path`".
     *
     * The direction matters and is easy to invert. This does not ask "did every claim find
     * a value" — it asks "did every value have a claim". An uncovered field is one the
     * issuer never described, so the wallet has no label for it and would either hide it
     * or show it raw; both mean the user consents to something they were not shown.
     *
     * A claim path *prefixes* the fields it covers, so a claim on `payee` covers
     * `payee.name`. The `#integrity` companion of an image claim is covered by that claim
     * explicitly, per the spec's parenthetical.
     */
    private fun coverageViolation(
        claims: List<ClaimMetadata>,
        payload: JsonObject,
    ): IncompatibilityReason? {
        val claimPaths = claims.map { it.path.filterNotNull() }
        val integrityPaths =
            claims
                .filter { it.valueType == IMAGE_VALUE_TYPE }
                .mapNotNull { claim ->
                    val concrete = claim.path.filterNotNull()
                    concrete.lastOrNull()?.let { leaf -> concrete.dropLast(1) + "$leaf$INTEGRITY_SUFFIX" }
                }
        val covered = claimPaths + integrityPaths

        for (field in leafPaths(payload)) {
            if (covered.none { it.size <= field.size && field.subList(0, it.size) == it }) {
                return reason(
                    IncompatibilityReason.Code.PAYLOAD_FIELD_UNCOVERED,
                    "payload field '${field.joinToString(".")}' is not covered by any claim path",
                )
            }
        }
        return null
    }

    /**
     * Every leaf field path in [obj], descending only through non-empty objects.
     *
     * A field whose value is an empty object, an array, or a scalar is itself a leaf: it
     * is a field the issuer must have declared, even when there is nothing beneath it.
     * An empty object at the root yields no paths at all — an empty payload has no
     * fields, so there is nothing for coverage to object to. (Getting that wrong makes
     * an absent mandatory field report as an uncovered one, which is the opposite
     * diagnosis.)
     *
     * Arrays are leaves for now; descending into them is what wildcard expansion adds.
     */
    private fun leafPaths(obj: JsonObject): List<List<String>> =
        obj.flatMap { (key, child) ->
            if (child is JsonObject && child.isNotEmpty()) {
                leafPaths(child).map { listOf(key) + it }
            } else {
                listOf(listOf(key))
            }
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

    private fun reason(
        code: IncompatibilityReason.Code,
        detail: String,
    ) = IncompatibilityReason(code, detail)

    private fun IncompatibilityReason.asResult() = ValidationResult.Incompatible(this)

    private companion object {
        const val IMAGE_VALUE_TYPE = "image"
        const val INTEGRITY_SUFFIX = "#integrity"
        const val MINI_MARKDOWN = "mini_markdown"
        const val TEMPLATE_MINI_MARKDOWN = "template:mini_markdown"

        /**
         * §3.3: "A `display` entry's `display_type` and a `ui_labels` entry's `value_type`
         * MUST be either `mini_markdown` or `template:mini_markdown` ... or absent." The
         * value types that produce non-textual or standalone content — `image`, `url`,
         * `label_only` — must never appear on a label.
         */
        val ALLOWED_LABEL_TYPES = setOf(MINI_MARKDOWN, TEMPLATE_MINI_MARKDOWN)
    }
}
