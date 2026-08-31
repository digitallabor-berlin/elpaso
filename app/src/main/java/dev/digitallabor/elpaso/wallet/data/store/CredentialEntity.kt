package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import java.time.Instant

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val format: String,
    val configurationId: String,
    val issuerId: String,
    val displayName: String,
    val displayMetadataJson: String,
    val payload: ByteArray,
    val deviceKeyAlias: String,
    val issuedAt: Long,
    val expiresAt: Long?,
    val lastUsedAt: Long?,
    val usageCount: Int,
    val issuerBinding: String? = null,
    val issuerKeySetSource: String? = null,
) {
    fun toDomain(): Credential = Credential(
        id = id,
        format = Format.fromWire(format) ?: error("Unknown format $format for credential $id"),
        configurationId = configurationId,
        issuerId = issuerId,
        displayName = displayName,
        displayMetadataJson = displayMetadataJson,
        payload = payload,
        deviceKeyAlias = deviceKeyAlias,
        issuedAt = Instant.ofEpochMilli(issuedAt),
        expiresAt = expiresAt?.let(Instant::ofEpochMilli),
        lastUsedAt = lastUsedAt?.let(Instant::ofEpochMilli),
        usageCount = usageCount,
        issuerBinding = IssuerBinding.fromWire(issuerBinding),
        issuerKeySetSource = issuerKeySetSource,
    )

    companion object {
        fun fromDomain(c: Credential): CredentialEntity = CredentialEntity(
            id = c.id,
            format = c.format.wire,
            configurationId = c.configurationId,
            issuerId = c.issuerId,
            displayName = c.displayName,
            displayMetadataJson = c.displayMetadataJson,
            payload = c.payload,
            deviceKeyAlias = c.deviceKeyAlias,
            issuedAt = c.issuedAt.toEpochMilli(),
            expiresAt = c.expiresAt?.toEpochMilli(),
            lastUsedAt = c.lastUsedAt?.toEpochMilli(),
            usageCount = c.usageCount,
            issuerBinding = c.issuerBinding?.wire,
            issuerKeySetSource = c.issuerKeySetSource,
        )
    }
}
