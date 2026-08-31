package dev.digitallabor.elpaso.wallet.domain.model

/**
 * How a stored credential's issuer signature was actually verified, recorded at
 * issuance.
 *
 * Distinct from `data.trust.SignatureMechanism`, and deliberately so. That enum is
 * *policy* — what the wallet permits for an issuer, read from `trusted_issuers.json`.
 * This one is *history* — what happened to this credential, on this device, at the
 * moment it was stored. They are not the same fact: an issuer's policy can be edited
 * after a credential was issued, and the binding rule of paso-proof-metadata.md §7
 * step 6 must follow the credential, not the current asset.
 *
 * Null means "issued before the wallet verified credentials at all". Such a credential
 * has no anchor, so §7 step 6's key-set branch does not apply to it — see
 * `IssuerSignedJwt.bindToKeySet`. It deliberately does **not** default to [X5c]: a
 * default would apply a certificate binding rule to a credential nothing ever checked.
 */
enum class IssuerBinding(
    val wire: String,
) {
    /** Verified against the end-entity certificate in the credential's `x5c` header. */
    X5c("x5c"),

    /** Verified against a key from the issuer's published JWK Set. */
    KeySet("key_set"),
    ;

    companion object {
        fun fromWire(value: String?): IssuerBinding? = entries.firstOrNull { it.wire == value }
    }
}
