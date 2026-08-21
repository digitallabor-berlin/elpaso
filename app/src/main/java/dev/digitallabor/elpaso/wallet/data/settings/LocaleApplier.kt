package dev.digitallabor.elpaso.wallet.data.settings

import android.content.Context
import android.content.res.Configuration
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
        val list = if (pref == LanguagePreference.System) {
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
    fun wrap(base: Context, pref: LanguagePreference): Context {
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
        val tag = when (pref) {
            LanguagePreference.German -> "de"
            LanguagePreference.English -> "en"
            LanguagePreference.French -> "fr"
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
}
