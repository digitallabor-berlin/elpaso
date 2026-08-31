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
    /**
     * Which Issuer Signature Mechanism verified this credential at issuance, or null for
     * a credential stored before the wallet verified credentials. This is the value both
     * PaSO metadata verifiers dispatch on — never a JOSE header (spec §5.8, §10.2).
     */
    val issuerBinding: IssuerBinding? = null,
    /**
     * The well-known or `jwks_uri` URL whose key set verified this credential. Null
     * under [IssuerBinding.X5c]. paso-proof-metadata.md §7 step 6 requires "the same
     * issuer key set", not merely one belonging to the same issuer, and this is how
     * sameness is established.
     */
    val issuerKeySetSource: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Credential) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
