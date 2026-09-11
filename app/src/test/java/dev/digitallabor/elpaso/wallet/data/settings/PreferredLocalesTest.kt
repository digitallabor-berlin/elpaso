package dev.digitallabor.elpaso.wallet.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * PaSO View §4 asks for "the user's languages", which is not the same question as "which
 * of the three translations does the app chrome render in". [LocaleApplier.effectiveLocale]
 * answers the second — it clamps to a shipped language and drops the region — and feeding
 * its answer into §4 made a bare `en` range the only thing the wallet could ever match.
 * RFC4647 §3.4 Lookup truncates the *range*, never the tag, so `en` does not match an
 * `en-US` display entry and a perfectly conformant en-US metadata blob was refused.
 *
 * These cases pin the region down where the platform actually knows it.
 */
class PreferredLocalesTest {
    private fun tags(
        pref: LanguagePreference,
        vararg device: String,
    ) = PreferredLocales
        .resolve(pref, device.map { Locale.forLanguageTag(it) })
        .map { it.toLanguageTag() }

    @Test
    fun systemKeepsTheDeviceListWithItsRegions() {
        assertEquals(listOf("en-US", "de-DE"), tags(LanguagePreference.System, "en-US", "de-DE"))
    }

    @Test
    fun systemOnEmptyDeviceListIsEmpty() {
        // The caller appends the English fallback; inventing one here would hide that.
        assertEquals(emptyList<String>(), tags(LanguagePreference.System))
    }

    @Test
    fun explicitChoiceIsRefinedByTheDeviceRegionForTheSameLanguage() {
        // The user picked "English" in Settings on an en-US device: en-US is a strictly
        // better range than en, because Lookup truncates it back to en anyway.
        assertEquals(listOf("en-US", "en"), tags(LanguagePreference.English, "en-US"))
    }

    @Test
    fun explicitChoiceIgnoresDeviceLocalesOfOtherLanguages() {
        assertEquals(listOf("de"), tags(LanguagePreference.German, "en-US", "fr-FR"))
    }

    @Test
    fun explicitChoiceKeepsEveryMatchingRegionInDeviceOrder() {
        assertEquals(
            listOf("en-GB", "en-US", "en"),
            tags(LanguagePreference.English, "en-GB", "en-US", "de-DE"),
        )
    }

    @Test
    fun aDeviceLocaleThatIsAlreadyBareIsNotDuplicated() {
        assertEquals(listOf("en"), tags(LanguagePreference.English, "en"))
    }

    @Test
    fun undeterminedDeviceLocalesAreDropped() {
        // Locale.forLanguageTag("") yields a locale with an empty language; it is not a
        // language range and would make lookup() compare against "".
        assertEquals(listOf("de-DE"), tags(LanguagePreference.System, "", "de-DE"))
    }
}
