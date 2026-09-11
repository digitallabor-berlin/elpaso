package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import dev.digitallabor.elpaso.wallet.domain.model.ClaimDisplay
import dev.digitallabor.elpaso.wallet.domain.model.LocalizedLabel
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import java.util.Locale

/** The `ui_labels` element identifiers this specification defines (metadata §3.2). */
object UiLabelKeys {
    const val TRANSACTION_TITLE = "transaction_title"
    const val AFFIRMATIVE_ACTION = "affirmative_action_label"
    const val DENIAL_ACTION = "denial_action_label"
    const val SECURITY_HINT = "security_hint"
}

/**
 * The locale chosen for one transaction, together with the entries that matched it.
 *
 * Carrying the matched entries rather than just the tag is the point: it makes it
 * impossible for a later stage to re-pick a different entry for one array and quietly
 * reintroduce the mixed-language screen §4 exists to prevent. [claimDisplay] is keyed by a
 * claim's index in the `claims` array, which is stable and unique even when metadata
 * violates §3.3 by repeating a `path`.
 */
data class Selection(
    val locale: Locale,
    /** Reported in the `display_locale` holder-binding proof claim (PaSO Core). */
    val tag: String,
    val claimDisplay: Map<Int, ClaimDisplay>,
    val uiLabel: Map<String, LocalizedLabel>,
)

/**
 * PaSO View §4 locale selection.
 *
 * The wallet keeps an ordered list of the user's languages and, for each in turn, asks
 * whether *every* display array and every populated `ui_labels` array has an entry for it.
 * Only then is that locale selected; otherwise every match from it is discarded and the
 * next locale is tried. If none succeeds, the credential is excluded.
 *
 * **All-or-nothing is the whole idea.** The obvious implementation — let each array pick
 * its own best match — produces a consent screen with a German label above an English one,
 * and nothing on screen tells the user that happened. §4 forbids that shape outright, so
 * "no complete match" is a real outcome that removes the credential rather than a case to
 * paper over.
 *
 * Pure, and therefore JVM-testable: nothing here touches `android.*`.
 */
object LocaleSelector {
    /**
     * [RFC4647] §3.4 Lookup against one array.
     *
     * Tries the full language range, then progressively drops trailing subtags (skipping a
     * stranded single-character subtag, as §3.4 requires), and finally falls back to the
     * array's entry without a `locale`, which §4 defines as its default. Returns null when
     * the array offers neither.
     */
    fun <T> lookup(
        range: Locale,
        entries: List<T>,
        tagOf: (T) -> String?,
    ): T? {
        if (entries.isEmpty()) return null

        var candidate = range.toLanguageTag().lowercase(Locale.ROOT)
        while (candidate.isNotEmpty()) {
            entries.firstOrNull { tagOf(it)?.lowercase(Locale.ROOT) == candidate }?.let { return it }

            val cut = candidate.lastIndexOf('-')
            if (cut < 0) break
            candidate = candidate.substring(0, cut)

            // §3.4: "If the last subtag is a single character, remove it as well." A
            // singleton introduces an extension sequence, so leaving it dangling would
            // produce a tag that is not well-formed.
            val next = candidate.lastIndexOf('-')
            if (next >= 0 && candidate.length - next == 2) candidate = candidate.substring(0, next)
        }

        return entries.firstOrNull { tagOf(it).isNullOrBlank() }
    }

    /**
     * Runs the §4 procedure over [priority], returning the first locale every array agrees
     * on, or null when the credential must be excluded.
     */
    fun select(
        metadata: TransactionDataTypeMetadata,
        priority: List<Locale>,
    ): Selection? {
        for (locale in priority) {
            val claimDisplay = mutableMapOf<Int, ClaimDisplay>()
            var complete = true

            metadata.claims.forEachIndexed { index, claim ->
                // §3.1: a claim with no `display` array is an internal value. It has no
                // array to match, so requiring it to match would let an undisplayed field
                // veto every locale.
                if (!complete || claim.display.isEmpty()) return@forEachIndexed
                val matched = lookup(locale, claim.display) { it.locale }
                if (matched == null) complete = false else claimDisplay[index] = matched
            }
            if (!complete) continue

            val uiLabel = mutableMapOf<String, LocalizedLabel>()
            for ((key, entries) in populatedUiArrays(metadata.uiLabels)) {
                val matched = lookup(locale, entries) { it.locale }
                if (matched == null) {
                    complete = false
                    break
                }
                uiLabel[key] = matched
            }
            if (!complete) continue

            return Selection(
                locale = locale,
                tag = locale.toLanguageTag(),
                claimDisplay = claimDisplay,
                uiLabel = uiLabel,
            )
        }
        return null
    }

    /**
     * The user's languages in decreasing priority, with [fallback] appended so the list is
     * never empty. Duplicates are dropped, keeping the earliest (highest-priority)
     * occurrence.
     *
     * Kept pure — the caller supplies the platform's active locales — so the §4 procedure
     * stays unit-testable. `android.*` returns stubs under JVM unit tests, so reading the
     * platform list in here would make every test of this function vacuous.
     */
    fun localePriorityList(
        preferred: List<Locale>,
        fallback: Locale,
    ): List<Locale> = (preferred + fallback).distinctBy { it.toLanguageTag() }

    /** Only arrays that actually carry entries participate in matching (§4). */
    private fun populatedUiArrays(ui: UiLabels): List<Pair<String, List<LocalizedLabel>>> =
        listOf(
            UiLabelKeys.TRANSACTION_TITLE to ui.transactionTitle,
            UiLabelKeys.AFFIRMATIVE_ACTION to ui.affirmativeActionLabel,
            UiLabelKeys.DENIAL_ACTION to ui.denialActionLabel,
            UiLabelKeys.SECURITY_HINT to ui.securityHint,
        ).filter { (_, entries) -> entries.isNotEmpty() }
}
