package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import java.text.BreakIterator
import java.util.Locale

/**
 * Counts text in **extended grapheme clusters** per PaSO Proof Metadata §3.3 ([UAX29]).
 *
 * Counting `String.length` instead would be wrong in both directions that matter: a
 * combining accent or a surrogate-pair emoji spends two chars on one cluster, so a
 * conforming label could be rejected, while the caps exist precisely so a conforming
 * label always fits.
 *
 * **Why [java.text.BreakIterator] and not `android.icu.text.BreakIterator`:** the module
 * sets `testOptions.unitTests.isReturnDefaultValues = true`, so every `android.*` API
 * returns null/0/false under JVM unit tests. An ICU-backed counter would silently report
 * `0` for every label in the validator tests that depend on this — the caps would appear
 * to pass while measuring nothing. `java.text` is real on both the JVM and the device.
 *
 * The trade: `java.text` is less precise than ICU on some ZWJ emoji sequences and may
 * count one such sequence as several clusters. That errs toward rejecting a label sitting
 * right at the cap, which is the safe direction for a consent screen.
 */
fun interface GraphemeCounter {
    fun count(text: String): Int

    companion object {
        val Default =
            GraphemeCounter { text ->
                if (text.isEmpty()) {
                    0
                } else {
                    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
                    iterator.setText(text)
                    var clusters = 0
                    while (iterator.next() != BreakIterator.DONE) clusters++
                    clusters
                }
            }
    }
}

/**
 * The character prohibitions of PaSO Proof Metadata §3.3. They apply to every
 * human-readable label **and** to `transaction_data` `payload` string values — the
 * payload half is easy to forget, and it is the half a verifier controls.
 *
 * These are display-safety rules, not encoding rules: their purpose (View §5.1, §5.4) is
 * to stop metadata- or payload-supplied text from restructuring the consent screen or
 * impersonating wallet chrome.
 */
object LabelText {
    /**
     * C0 (U+0000–U+001F) or C1 (U+007F–U+009F) control characters.
     *
     * This is what forbids line breaks inside a label: §3.3 makes wrapping a rendering
     * decision of the wallet, so an issuer cannot force one.
     */
    fun hasControlChar(s: String): Boolean = s.any { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }

    /**
     * Directional embedding and override characters U+202A–U+202E.
     *
     * Banned outright because they persist past the end of the string they appear in and
     * can visually reorder text that follows — the classic way to make an amount or a
     * payee read as something other than what will be signed.
     */
    fun hasDirectionalOverride(s: String): Boolean = s.any { it.code in 0x202A..0x202E }

    /**
     * Directional **isolates** (U+2066 LRI, U+2067 RLI, U+2068 FSI) are permitted, but
     * §3.3 requires each to be "properly terminated by U+2069" (PDI). Isolates are safe
     * exactly because they are scoped; an unterminated one loses that property, and a
     * stray PDI would close a scope the label never opened.
     *
     * Returns true when every isolate is closed and no PDI underflows.
     */
    fun hasBalancedIsolates(s: String): Boolean {
        var depth = 0
        for (ch in s) {
            when (ch.code) {
                0x2066, 0x2067, 0x2068 -> {
                    depth++
                }

                0x2069 -> {
                    depth--
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0
    }
}
