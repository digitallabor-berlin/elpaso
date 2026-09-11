package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimMetadata
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

        // Rows, labels and the item count are populated as the later constraints land.
        // Returning an empty-but-valid plan keeps the type wiring honest in the meantime:
        // callers already receive the real verdict, just not yet the real content.
        return ValidationResult.Compatible(
            RenderPlan(
                title = null,
                rows = emptyList(),
                securityHint = null,
                affirmativeLabel = null,
                denialLabel = null,
                selectedLocaleTag = selection.localeTag,
                totalItemCount = 0,
            ),
        )
    }

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
    }
}
