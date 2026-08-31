package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import java.time.Instant

/**
 * An issuer's published JWK Set, resolved through the JWT VC Issuer Metadata mechanism
 * (draft-ietf-oauth-sd-jwt-vc-11 §5).
 *
 * [sourceUrl] is the URL the keys actually came from — the well-known URL for an inline
 * `jwks`, or the `jwks_uri` when one was followed. It is load-bearing rather than
 * diagnostic: paso-proof-metadata.md §7 step 6 requires a metadata JWT to be verified
 * by a key from "the same issuer key set that verifies the credential itself", and this
 * is how "the same key set" is identified. See `IssuerSignedJwt.bindToKeySet`.
 *
 * [jwksJson] is retained verbatim so the set can be persisted and re-parsed without
 * re-serialising Nimbus objects, and so a stored row is byte-comparable with a fresh
 * fetch.
 */
data class IssuerKeySet(
    val issuer: String,
    val keys: List<JWK>,
    val sourceUrl: String,
    val jwksJson: String,
    val fetchedAt: Instant,
) {
    /**
     * Candidate keys for a JWS. §5.2 only RECOMMENDS a `kid`, so a JWT without one is
     * legitimate and every key becomes a candidate; the caller then tries each. A `kid`
     * that matches nothing yields an empty list, which the caller must treat as a
     * failure rather than silently widening to the whole set — widening would make the
     * `kid` advisory and let a verifier steer key selection.
     */
    fun byKid(kid: String?): List<JWK> = if (kid == null) keys else keys.filter { it.keyID == kid }

    companion object {
        /** Parses [jwksJson] as an RFC 7517 JWK Set. Throws if it is malformed or empty. */
        fun parse(
            issuer: String,
            jwksJson: String,
            sourceUrl: String,
            fetchedAt: Instant,
        ): IssuerKeySet {
            val keys = JWKSet.parse(jwksJson).keys
            check(keys.isNotEmpty()) { "issuer key set from $sourceUrl contains no keys" }
            return IssuerKeySet(
                issuer = issuer,
                keys = keys.map { it.toPublicJWK() },
                sourceUrl = sourceUrl,
                jwksJson = jwksJson,
                fetchedAt = fetchedAt,
            )
        }
    }
}

/**
 * Resolves an issuer's key set. Two implementations exist and the difference is a
 * security property, not an optimisation: `CachingIssuerKeySetResolver` may fetch and
 * is used at issuance; `CacheOnlyIssuerKeySetResolver` cannot fetch and is used
 * everywhere a presentation might be in progress, because a fetch correlated with a
 * presentation is the paso-proof-metadata.md §8 linkability hazard.
 */
interface IssuerKeySetResolver {
    suspend fun resolve(
        issuerId: String,
        now: Instant,
    ): Result<IssuerKeySet>
}
