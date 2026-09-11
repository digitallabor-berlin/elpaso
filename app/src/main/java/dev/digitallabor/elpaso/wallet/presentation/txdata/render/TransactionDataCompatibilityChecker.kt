package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import kotlinx.serialization.json.JsonObject

/**
 * The single entry point the consent screen uses to ask "can I render this
 * `transaction_data` entry, and if so, what exactly do I draw?"
 *
 * PaSO Core §7.4.2 splits that question in two: step 2 is a pure conformance check over
 * the metadata and payload, and step 3 resolves any external resource whose integrity must
 * be verified. This type owns the sequence so the screen never has to know there is one —
 * it asks once and gets a [RenderPlan] or a refusal.
 *
 * Right now only step 2 exists, so this is a thin pass-through to the validator. It is a
 * separate type anyway because the step-3 resolution that follows needs I/O, and that will
 * make this `suspend` without any caller having to change how it thinks about the flow.
 */
class TransactionDataCompatibilityChecker(
    private val validator: TransactionDataValidator,
) {
    fun check(
        metadata: TransactionDataTypeMetadata,
        payload: JsonObject,
        selection: LocaleSelection,
    ): ValidationResult = validator.validate(metadata, payload, selection)
}
