package dev.digitallabor.elpaso.wallet.domain.model

import java.time.Instant

data class Credential(
    val id: String,
    val format: Format,
    val configurationId: String,
    val issuerId: String,
    val displayName: String,
    val displayMetadataJson: String,
    val payload: ByteArray,
    val deviceKeyAlias: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val usageCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Credential) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
