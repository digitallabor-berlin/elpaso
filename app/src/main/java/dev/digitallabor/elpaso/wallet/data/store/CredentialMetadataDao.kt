package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CredentialMetadataDao {
    @Query("SELECT * FROM credential_metadata WHERE credentialId = :credentialId")
    suspend fun forCredential(credentialId: String): List<CredentialMetadataEntity>

    @Query("SELECT * FROM credential_metadata WHERE credentialId = :credentialId AND locale = :locale")
    suspend fun forCredentialAndLocale(credentialId: String, locale: String): CredentialMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialMetadataEntity)

    @Query("DELETE FROM credential_metadata WHERE credentialId = :credentialId")
    suspend fun deleteForCredential(credentialId: String)

    @Query("DELETE FROM credential_metadata WHERE credentialId = :credentialId AND locale = :locale")
    suspend fun deleteForCredentialAndLocale(credentialId: String, locale: String)

    @Query("DELETE FROM credential_metadata WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM credential_metadata")
    suspend fun deleteAll()

    @Query("SELECT jwt FROM credential_metadata")
    suspend fun allJwts(): List<String>
}
