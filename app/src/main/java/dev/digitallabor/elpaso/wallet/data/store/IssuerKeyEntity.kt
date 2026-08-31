package dev.digitallabor.elpaso.wallet.data.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A cached JWT VC Issuer Metadata key set (draft-ietf-oauth-sd-jwt-vc-11 §5).
 *
 * Exists so that no presentation ever performs a key-set fetch: a fetch correlated with
 * a verifier interaction is the paso-proof-metadata.md §8 linkability hazard, so the
 * only writers are issuance and the boot-time refresh sweep.
 *
 * `jwksJson` is the verbatim JWK Set document, not a re-serialisation — that keeps a
 * stored row comparable with a fresh fetch, and keeps this table free of any opinion
 * about Nimbus's serialisation. `sourceUrl` is the well-known URL or the `jwks_uri` the
 * keys came from and is what §7 step 6's "same issuer key set" is checked against.
 *
 * One row per issuer: an issuer publishes one key set at one location, and keeping the
 * primary key on `issuerId` means a re-fetch replaces rather than accumulates.
 */
@Entity(tableName = "issuer_keys")
data class IssuerKeyEntity(
    @PrimaryKey val issuerId: String,
    val sourceUrl: String,
    val jwksJson: String,
    val fetchedAt: Long,
    val expiresAt: Long,
)
