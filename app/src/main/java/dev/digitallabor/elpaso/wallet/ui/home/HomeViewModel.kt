package dev.digitallabor.elpaso.wallet.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    credentials: CredentialRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    /**
     * The credential deck. The user can drag cards into any position, so the persisted
     * [SettingsRepository.walletOrder] decides the visible order; anything not yet in that
     * list is appended in `issuedAt` order so newly added credentials show up at the
     * bottom of the deck.
     */
    val items: StateFlow<List<Credential>> =
        combine(
            credentials.observeAll(),
            settings.walletOrder,
        ) { creds, savedOrder ->
            val byId = creds.associateBy { it.id }
            val ordered = savedOrder.mapNotNull { byId[it] }
            val seen = ordered.mapTo(mutableSetOf()) { it.id }
            val tail = creds.filter { it.id !in seen }.sortedBy { it.issuedAt }
            ordered + tail
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setOrder(orderedIds: List<String>) {
        viewModelScope.launch { settings.setWalletOrder(orderedIds) }
    }
}
