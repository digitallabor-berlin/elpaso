package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IssuerKeyDao {
    @Query("SELECT * FROM issuer_keys WHERE issuerId = :issuerId")
    suspend fun forIssuer(issuerId: String): IssuerKeyEntity?

    @Query("SELECT * FROM issuer_keys")
    suspend fun all(): List<IssuerKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IssuerKeyEntity)

    @Query("DELETE FROM issuer_keys WHERE issuerId = :issuerId")
    suspend fun deleteForIssuer(issuerId: String)

    @Query("DELETE FROM issuer_keys")
    suspend fun deleteAll()
}
