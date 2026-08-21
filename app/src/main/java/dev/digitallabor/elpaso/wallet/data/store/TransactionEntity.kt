package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val id: String,
    val verifierId: String,
    val verifierLabel: String,
    val credentialId: String?,
    val fieldsDisclosed: String,
    val transactionSummary: String?,
    val outcome: String,
    val timestamp: Long,
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(entity: TransactionEntity)
}
