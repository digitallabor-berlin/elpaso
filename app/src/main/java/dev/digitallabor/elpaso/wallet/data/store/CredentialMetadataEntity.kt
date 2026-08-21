package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Persisted signed credential metadata JWT (paso-proof-metadata.md §5: "SHALL persist
 * signed credential metadata JWTs in their signed form and SHALL NOT persist the
 * decoded credential metadata"). The wallet MAY hold multiple rows per credential to
 * cover different locales (spec §5).
 *
 * `expiresAt` mirrors the JWT's `exp` claim so we can prune without re-parsing.
 * `metadataUri` is preserved so the renewal worker can re-fetch (spec §7).
 */
@Entity(
    tableName = "credential_metadata",
    primaryKeys = ["credentialId", "locale"],
    foreignKeys = [
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("credentialId")],
)
data class CredentialMetadataEntity(
    val credentialId: String,
    val locale: String,
    val jwt: String,
    val expiresAt: Long,
    val metadataUri: String,
)
