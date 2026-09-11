package dev.digitallabor.elpaso.wallet.data.settings

import java.util.Locale

/**
 * The user's languages in decreasing priority, as PaSO View §4 means the phrase.
 *
 * This is deliberately **not** [LocaleApplier.effectiveLocale]. That function answers
 * "which of the three translations do I render the app chrome in", so it clamps to a
 * shipped language and returns a bare `Locale(tag)` with the region discarded. §4 asks a
 * different question — what languages will this user accept from an *issuer* — and the
 * region is exactly the part that must survive, because RFC4647 §3.4 Lookup truncates the
 * language **range** and never the tag. A range of `en` therefore does not match an
 * `en-US` display entry, and feeding the chrome locale into §4 refused every conformant
 * metadata blob that tagged its labels with a region.
 *
 * Keeping the region is free in the other direction: a range of `en-US` is truncated back
 * to `en` by [dev.digitallabor.elpaso.wallet.presentation.txdata.render.LocaleSelector.lookup],
 * so it matches everything bare `en` matched and more.
 *
 * Pure, and separated from [LocaleApplier] for that reason: `android.*` returns stubs
 * under JVM unit tests, so reading the platform list in here would make every test of this
 * function vacuous. The caller supplies the device list.
 */
object PreferredLocales {
    /**
     * @param deviceLocales the platform's locale list, highest priority first, regions intact
     * @return ranges to try in order; possibly empty, since the §4 caller appends its own
     *   fallback and inventing one here would hide that the device offered nothing
     */
    fun resolve(
        pref: LanguagePreference,
        deviceLocales: List<Locale>,
    ): List<Locale> {
        // An undetermined locale has an empty language subtag, which is not a language
        // range at all — lookup() would compare display entries against "".
        val device = deviceLocales.filter { it.language.isNotBlank() }
        val ranges =
            when (pref) {
                // No explicit choice: the platform list *is* the answer, in its own order.
                LanguagePreference.System -> {
                    device
                }

                // An explicit choice fixes the language but says nothing about region, so
                // take the region from whichever device locales speak that language, then
                // the bare tag as the final, least specific range for that language.
                else -> {
                    device.filter { it.language.equals(pref.tag, ignoreCase = true) } +
                        Locale(pref.tag)
                }
            }
        return ranges.distinctBy { it.toLanguageTag().lowercase(Locale.ROOT) }
    }
}
