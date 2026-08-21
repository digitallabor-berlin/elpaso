package dev.digitallabor.elpaso.wallet.data.store

import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CredentialRepository(
    private val dao: CredentialDao,
    private val keyManager: KeyManager,
) {
    fun observeAll(): Flow<List<Credential>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun byId(id: String): Credential? = dao.byId(id)?.toDomain()

    suspend fun insert(credential: Credential) {
        dao.insert(CredentialEntity.fromDomain(credential))
    }

    suspend fun delete(credential: Credential) {
        dao.delete(credential.id)
        runCatching { keyManager.deleteDeviceKey(credential.deviceKeyAlias) }
    }

    suspend fun markUsed(credential: Credential) {
        dao.markUsed(credential.id, Instant.now().toEpochMilli())
    }
}
