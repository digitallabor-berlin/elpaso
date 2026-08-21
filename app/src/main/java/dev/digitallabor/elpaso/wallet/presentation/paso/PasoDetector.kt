package dev.digitallabor.elpaso.wallet.presentation.paso

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData

/**
 * Identifies PaSO-targeted `transaction_data` entries (PaSO Core §5.2: `type` starts with
 * `urn:paso:sca:`) and enforces the simple-profile constraint from §7.3.
 *
 * The wallet currently implements the simple profile only — a request that contains more
 * than one PaSO-targeted entry is rejected so the user isn't surprised by misaligned
 * consent UI. §7.4 (advanced profile, multiple credentials/transaction_data alternatives)
 * is out of scope for this iteration.
 */
object PasoDetector {

    class PasoUnsupportedException(val reasonStringRes: Int, message: String) : RuntimeException(message)

    fun pasoEntry(entries: List<TransactionData>): TransactionData? =
        entries.firstOrNull { it.isPaso() }

    fun rejectAdvancedProfile(entries: List<TransactionData>) {
        val pasoCount = entries.count { it.isPaso() }
        if (pasoCount > 1) {
            throw PasoUnsupportedException(
                reasonStringRes = dev.digitallabor.elpaso.wallet.R.string.paso_multiple_entries_not_supported,
                message = "Wallet implements PaSO simple profile only; request contained $pasoCount PaSO entries",
            )
        }
    }
}
