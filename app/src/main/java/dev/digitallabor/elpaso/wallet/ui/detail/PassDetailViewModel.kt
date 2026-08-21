package dev.digitallabor.elpaso.wallet.ui.detail

import androidx.lifecycle.ViewModel
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential

class PassDetailViewModel(private val repository: CredentialRepository) : ViewModel() {

    data class State(val credential: Credential, val claims: CredentialClaims.Extracted)

    suspend fun load(id: String): State? = repository.byId(id)?.let { credential ->
        State(credential, CredentialClaims.extract(credential))
    }

    suspend fun remove(c: Credential) = repository.delete(c)
}
