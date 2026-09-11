package dev.digitallabor.elpaso.wallet.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Single source of truth for "make Android use the user's preferred language."
 *
 * Uses `AppCompatDelegate.setApplicationLocales(...)` — the back-ported per-app
 * locale API. Works on API 29+ (our minSdk) without needing AppCompat themes;
 * the only AppCompat dependency we pull in is the `app` library itself.
 *
 * The call schedules an activity recreation; the next composition reads from
 * `values-de/` or `values/` automatically.
 */
object LocaleApplier {
    fun apply(pref: LanguagePreference) {
        val list =
            if (pref == LanguagePreference.System) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(pref.tag)
            }
        AppCompatDelegate.setApplicationLocales(list)
    }

    /**
     * Wrap a base [Context] with a Configuration that has the requested locale set.
     * Called from `MainActivity.attachBaseContext` so the activity's resources load
     * from the right `values-xx` bucket. AppCompatDelegate's own resource swap only
     * fires inside AppCompatActivity subclasses — we extend FragmentActivity, so we
     * do this ourselves.
     */
    fun wrap(
        base: Context,
        pref: LanguagePreference,
    ): Context {
        if (pref == LanguagePreference.System) return base
        val locale = Locale(pref.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Resolve the effective UI locale right now, clamped to the two languages we
     * actually ship strings for. Used by [dev.digitallabor.elpaso.wallet.ai.llm.PromptBuilder]
     * and [dev.digitallabor.elpaso.wallet.ai.context.ContextRenderer] so the AI side stays in
     * sync with the UI side.
     */
    fun effectiveLocale(pref: LanguagePreference): Locale {
        val tag =
            when (pref) {
                LanguagePreference.German -> {
                    "de"
                }

                LanguagePreference.English -> {
                    "en"
                }

                LanguagePreference.French -> {
                    "fr"
                }

                LanguagePreference.System -> {
                    // Pick the first locale of the active list (AppCompat-managed),
                    // clamped to the languages we ship strings for. Anything else
                    // falls through to English.
                    val active = AppCompatDelegate.getApplicationLocales()
                    val device = if (!active.isEmpty) active.get(0) else Locale.getDefault()
                    when (device?.language) {
                        "de" -> "de"
                        "fr" -> "fr"
                        else -> "en"
                    }
                }
            }
        return Locale(tag)
    }

    /**
     * The user's languages for PaSO View §4 locale selection — regions intact, unclamped.
     *
     * Distinct from [effectiveLocale] on purpose; see [PreferredLocales] for why the region
     * must survive. The platform read lives here rather than there because `android.*`
     * returns stubs under JVM unit tests, which would make any test of the ordering rules
     * vacuous.
     *
     * Both lists are consulted, app-locale override first. The override is what the user
     * chose in Settings and so ranks highest, but [apply] writes it as a bare language tag
     * (`en`), which is precisely the region-less form the §4 lookup cannot match against a
     * regionful display entry. The system list is where the region actually lives, so it
     * follows as the refinement. [PreferredLocales.resolve] filters and de-duplicates.
     */
    fun preferredLocales(pref: LanguagePreference): List<Locale> = PreferredLocales.resolve(pref, deviceLocales())

    private fun deviceLocales(): List<Locale> =
        (
            AppCompatDelegate.getApplicationLocales().toList() +
                Resources
                    .getSystem()
                    .configuration.locales
                    .toList()
        )

    private fun LocaleListCompat.toList(): List<Locale> = (0 until size()).mapNotNull { get(it) }

    private fun android.os.LocaleList.toList(): List<Locale> = (0 until size()).mapNotNull { get(it) }
}
