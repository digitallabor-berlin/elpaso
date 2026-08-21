package dev.digitallabor.elpaso.wallet.data.store

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val dao: TransactionDao) {

    fun observeAll(): Flow<List<TransactionEntity>> = dao.observeAll()

    suspend fun log(
        verifierId: String,
        verifierLabel: String,
        credentialId: String?,
        fieldsDisclosed: List<String>,
        transactionSummary: String?,
        outcome: String,
    ) {
        dao.insert(
            TransactionEntity(
                id = UUID.randomUUID().toString(),
                verifierId = verifierId,
                verifierLabel = verifierLabel,
                credentialId = credentialId,
                fieldsDisclosed = fieldsDisclosed.joinToString(","),
                transactionSummary = transactionSummary,
                outcome = outcome,
                timestamp = Instant.now().toEpochMilli(),
            )
        )
    }
}
