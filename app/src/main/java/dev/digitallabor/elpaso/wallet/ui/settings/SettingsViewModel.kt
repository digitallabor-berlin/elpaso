package dev.digitallabor.elpaso.wallet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitallabor.elpaso.wallet.data.settings.LanguagePreference
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.settings.ThemePreference
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val metadataRepository: CredentialMetadataRepository,
) : ViewModel() {
    val developerMode: StateFlow<Boolean> =
        repository.developerMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themePreference: StateFlow<ThemePreference> =
        repository.themePreference
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemePreference.System)

    val languagePreference: StateFlow<LanguagePreference> =
        repository.languagePreference
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LanguagePreference.System)

    /**
     * Fires after a language change has been persisted and applied. The Settings
     * screen collects this and calls `activity.recreate()` so the new locale's
     * resources load from `values-xx`. The wait until the DataStore write completes
     * matters — recreating before persistence would race the new activity's
     * `attachBaseContext` against an old DataStore value.
     */
    private val _recreateRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recreateRequest: SharedFlow<Unit> = _recreateRequest.asSharedFlow()

    val metadataCacheEnabled: StateFlow<Boolean> =
        repository.metadataCacheEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val metadataCacheTtl: StateFlow<MetadataCacheTtl> =
        repository.metadataCacheTtl
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetadataCacheTtl.JwtExpiry)

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { repository.setDeveloperMode(enabled) }
    }

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { repository.setThemePreference(preference) }
    }

    /**
     * Persist the new language and immediately apply it via AppCompatDelegate — that
     * triggers an activity recreation, so the next composition reads from
     * `values-de/` or `values/` automatically.
     */
    fun setLanguagePreference(preference: LanguagePreference) {
        viewModelScope.launch {
            repository.setLanguagePreference(preference)
            LocaleApplier.apply(preference)
            _recreateRequest.tryEmit(Unit)
        }
    }

    /**
     * Toggle the metadata cache. Turning OFF also wipes the on-disk rows so the
     * user's mental model — "off means nothing is cached" — matches reality. Reads
     * already skip the DAO in disabled mode, but stray rows would resurface the
     * moment the user flips the switch back on, which would be surprising.
     */
    fun setMetadataCacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setMetadataCacheEnabled(enabled)
            if (!enabled) metadataRepository.clearAll()
        }
    }

    fun setMetadataCacheTtl(ttl: MetadataCacheTtl) {
        viewModelScope.launch { repository.setMetadataCacheTtl(ttl) }
    }

    /** Manual "Clear cached metadata" button — wipes every row regardless of TTL. */
    fun clearMetadataCache() {
        viewModelScope.launch { metadataRepository.clearAll() }
    }
}
