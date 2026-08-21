package dev.digitallabor.elpaso.wallet.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.walletSettings: DataStore<Preferences> by preferencesDataStore(name = "wallet_settings")

enum class ThemePreference {
    System,
    Light,
    Dark,
    ;

    companion object {
        fun fromKey(value: String?): ThemePreference =
            when (value) {
                Light.name -> Light
                Dark.name -> Dark
                else -> System
            }
    }
}

/**
 * Wallet-side cap on how long verified credential metadata JWTs may live in the local
 * cache, in addition to the JWT's own `exp` claim. The stored row is treated as
 * expired once `min(jwtExp, fetchedAt + ttl) <= now`. Picked from the settings screen.
 *
 * [JwtExpiry] keeps the prior behaviour (trust the issuer's exp). The bounded values
 * give the user a way to force more frequent re-fetches without disabling the cache
 * entirely; when the cache is turned off this setting is ignored.
 */
enum class MetadataCacheTtl(
    val durationMillis: Long?,
) {
    JwtExpiry(null),
    OneHour(60L * 60 * 1000),
    OneDay(24L * 60 * 60 * 1000),
    OneWeek(7L * 24 * 60 * 60 * 1000),
    ;

    companion object {
        fun fromKey(value: String?): MetadataCacheTtl =
            when (value) {
                OneHour.name -> OneHour
                OneDay.name -> OneDay
                OneWeek.name -> OneWeek
                else -> JwtExpiry
            }
    }
}

/**
 * UI language the user has chosen. [System] follows the device locale and falls back
 * to English for any non-German device. The mapped BCP-47 [tag] is what we hand to
 * `AppCompatDelegate.setApplicationLocales`.
 */
enum class LanguagePreference(
    val tag: String,
) {
    System(""),
    English("en"),
    German("de"),
    French("fr"),
    ;

    companion object {
        fun fromKey(value: String?): LanguagePreference =
            when (value) {
                English.name -> English
                German.name -> German
                French.name -> French
                else -> System
            }
    }
}

class SettingsRepository(
    context: Context,
) {
    private val store: DataStore<Preferences> = context.applicationContext.walletSettings

    // Default `true` for the demo build: skips the trust-list gate on issuance and
    // presentation so first-run users can talk to dev issuers/verifiers without
    // pre-seeding fingerprints. Productionising the wallet means flipping this back
    // to `false` and curating `trusted_issuers.json` / `trusted_verifiers.json`.
    val developerMode: Flow<Boolean> = store.data.map { it[DEVELOPER_MODE] ?: true }

    val themePreference: Flow<ThemePreference> =
        store.data.map {
            ThemePreference.fromKey(it[THEME_PREFERENCE])
        }

    val languagePreference: Flow<LanguagePreference> =
        store.data.map {
            LanguagePreference.fromKey(it[LANGUAGE_PREFERENCE])
        }

    /**
     * User-defined ordering of wallet items (credentials + bank accounts) on the home
     * deck. Stored as newline-separated IDs — wallet IDs never contain `\n`. Items not
     * present in this list are appended to the deck in their natural `createdAt` order;
     * IDs whose underlying item no longer exists are tolerated and skipped on read.
     */
    val walletOrder: Flow<List<String>> =
        store.data.map { prefs ->
            prefs[WALLET_ORDER]
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }

    /**
     * Whether the wallet may persist verified credential metadata JWTs locally. When
     * disabled, [CredentialMetadataRepository] bypasses the Room cache on both read
     * and upsert — every read goes back to the issuer. Default `true`.
     */
    val metadataCacheEnabled: Flow<Boolean> = store.data.map { it[METADATA_CACHE_ENABLED] ?: true }

    /**
     * Wallet-side cap on how long a cached row may live, on top of the JWT's `exp`.
     * Default [MetadataCacheTtl.JwtExpiry] (trust the issuer).
     */
    val metadataCacheTtl: Flow<MetadataCacheTtl> =
        store.data.map {
            MetadataCacheTtl.fromKey(it[METADATA_CACHE_TTL])
        }

    suspend fun setDeveloperMode(enabled: Boolean) {
        store.edit { it[DEVELOPER_MODE] = enabled }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        store.edit { it[THEME_PREFERENCE] = preference.name }
    }

    suspend fun setLanguagePreference(preference: LanguagePreference) {
        store.edit { it[LANGUAGE_PREFERENCE] = preference.name }
    }

    /** Synchronous read for app startup — we need the value before any Flow can deliver it. */
    suspend fun currentLanguagePreference(): LanguagePreference = languagePreference.first()

    suspend fun setWalletOrder(order: List<String>) {
        val cleaned =
            order
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && '\n' !in it }
                .distinct()
                .toList()
        store.edit {
            if (cleaned.isEmpty()) {
                it.remove(WALLET_ORDER)
            } else {
                it[WALLET_ORDER] = cleaned.joinToString("\n")
            }
        }
    }

    suspend fun setMetadataCacheEnabled(enabled: Boolean) {
        store.edit { it[METADATA_CACHE_ENABLED] = enabled }
    }

    suspend fun setMetadataCacheTtl(ttl: MetadataCacheTtl) {
        store.edit { it[METADATA_CACHE_TTL] = ttl.name }
    }

    /** Synchronous read used by the cache layer on every metadata fetch / read. */
    suspend fun currentMetadataCacheEnabled(): Boolean = metadataCacheEnabled.first()

    suspend fun currentMetadataCacheTtl(): MetadataCacheTtl = metadataCacheTtl.first()

    companion object {
        private val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val WALLET_ORDER = stringPreferencesKey("wallet_order")
        private val METADATA_CACHE_ENABLED = booleanPreferencesKey("metadata_cache_enabled")
        private val METADATA_CACHE_TTL = stringPreferencesKey("metadata_cache_ttl")
    }
}
