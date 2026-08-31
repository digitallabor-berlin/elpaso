package dev.digitallabor.elpaso.wallet.data.trust

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Issuer Signature Mechanism the wallet permits for one issuer, per
 * draft-ietf-oauth-sd-jwt-vc-11 §3.5.
 *
 * This is **policy, not observation**. §10.2 requires that "for any given `iss` value,
 * an attacker cannot influence the type of verification method", which rules out the
 * obvious implementation of trying `x5c` and falling back to a key set: whoever
 * composes the JOSE header would then be choosing the mechanism. So the mechanism is
 * declared per issuer in `trusted_issuers.json` and is never inferred from a header —
 * see `CredentialSignatureVerifier`, which dispatches on this value and rejects a
 * credential whose header disagrees with it.
 *
 * There is deliberately no default and no third "either" value.
 */
@Serializable
enum class SignatureMechanism {
    /** §3.5 X.509 Certificates: the issuer is the subject of the end-entity certificate. */
    @SerialName("x5c")
    X5c,

    /** §3.5 JWT VC Issuer Metadata: the key comes from the issuer's published JWK Set. */
    @SerialName("jwt_vc_issuer_metadata")
    JwtVcIssuerMetadata,
}
