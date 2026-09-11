package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The single entry point the consent screen uses to ask "can I render this
 * `transaction_data` entry, and if so, what exactly do I draw?"
 *
 * It runs the two procedures that decide an entry's fate, in the order the specs define
 * them: PaSO View §4 picks the locale (or excludes the credential outright), then PaSO
 * Core §7.4.2 step 2 checks conformance and builds the plan. Step 3 — resolving external
 * resources whose integrity must be verified — joins the sequence here later, which is why
 * this is a type rather than a free function: adding I/O will make it `suspend` without
 * any caller having to change how it thinks about the flow.
 *
 * Selection runs *first* deliberately. Validation needs the matched entries to know which
 * label text to measure, and letting it pick its own would reintroduce the per-array
 * locale drift §4 exists to prevent.
 */
class TransactionDataCompatibilityChecker(
    private val validator: TransactionDataValidator,
) {
    /**
     * @param priority the user's languages in decreasing order, per PaSO View §4
     */
    fun check(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        priority: List<Locale>,
    ): ValidationResult {
        val selection =
            LocaleSelector.select(metadata, priority)
                ?: return ValidationResult.Incompatible(
                    IncompatibilityReason(
                        IncompatibilityReason.Code.NO_LOCALE_MATCH,
                        "no locale in the priority list matched every display array",
                    ),
                )
        return validator.validate(metadata, payload, selection)
    }
}
