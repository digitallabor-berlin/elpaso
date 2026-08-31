package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import com.nimbusds.jose.jwk.AsymmetricJWK
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.vct.SdJwtHeaderReader
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * What verified a credential's issuer signature, and enough of it to bind a metadata
 * JWT to the same anchor later (paso-proof-metadata.md §7 step 6).
 */
sealed interface VerifiedIssuerBinding {
    /** The recorded form, for `Credential.issuerBinding`. */
    val binding: IssuerBinding

    /** The recorded key-set URL, for `Credential.issuerKeySetSource`. Null for x5c. */
    val keySetSource: String?

    data class X5c(
        val chain: List<X509Certificate>,
    ) : VerifiedIssuerBinding {
        override val binding = IssuerBinding.X5c
        override val keySetSource: String? = null
    }

    data class KeySet(
        val sourceUrl: String,
        val keyThumbprint: String,
    ) : VerifiedIssuerBinding {
        override val binding = IssuerBinding.KeySet
        override val keySetSource: String = sourceUrl
    }
}

/**
 * Verifies an SD-JWT-VC's issuer-signed JWT under the one Issuer Signature Mechanism the
 * trust list permits for its issuer (draft-ietf-oauth-sd-jwt-vc-11 §3.5).
 *
 * **The dispatch is on policy, not on the header.** §10.2 requires that "for any given
 * `iss` value, an attacker cannot influence the type of verification method", which rules
 * out the natural-looking implementation of trying `x5c` and falling back to a `kid`.
 * Concretely:
 *
 * - Under [SignatureMechanism.X5c], a missing `x5c` is a **rejection**, not a reason to
 *   look for a key set.
 * - Under [SignatureMechanism.JwtVcIssuerMetadata], a *present* `x5c` is a **rejection**
 *   too — an attempt to steer the wallet onto the other mechanism, logged as such.
 *
 * An issuer with no trust-list entry declares no mechanism and so cannot be verified at
 * all; that is a rejection rather than a default, because a default here is a policy
 * decision nobody made.
 *
 * `mso_mdoc` is refused with an explicit "not yet implemented": an ISO 18013-5 MSO is
 * signed with COSE_Sign1 over CBOR, a different primitive, and the spec defers it (§5.6,
 * §11). Refusing loudly is correct here even though `IssuanceClient` does not call this
 * for mdoc — a future caller must not discover the gap by getting a silent success.
 */
class CredentialSignatureVerifier(
    private val trustList: TrustListService,
    private val keySetResolver: IssuerKeySetResolver,
) {
    suspend fun verify(
        format: Format,
        payload: ByteArray,
        issuerId: String,
        now: Instant = Instant.now(),
    ): Result<VerifiedIssuerBinding> =
        runCatching {
            check(format == Format.SdJwtVc) {
                "credential issuer-signature verification for $format is not yet implemented"
            }

            val mechanism =
                trustList.mechanismFor(issuerId)
                    ?: error("issuer $issuerId declares no signature mechanism (absent from the trust list)")

            val issuerJwt =
                SdJwtHeaderReader.issuerJwt(payload)
                    ?: error("credential from $issuerId has no issuer-signed JWT segment")
            val signed = SignedJWT.parse(issuerJwt)

            when (mechanism) {
                SignatureMechanism.X5c -> verifyByX5c(signed, issuerId, now)
                SignatureMechanism.JwtVcIssuerMetadata -> verifyByKeySet(signed, issuerId, now)
            }
        }.onFailure {
            Log.w(LOG_TAG, "credential issuer signature rejected for $issuerId", it)
        }

    /** §3.5 X.509 Certificates. The issuer is the subject of the end-entity certificate. */
    private fun verifyByX5c(
        signed: SignedJWT,
        issuerId: String,
        now: Instant,
    ): VerifiedIssuerBinding {
        // Absence is a rejection. readX5cChain already errors on a missing header; the
        // point of stating it here is that there is deliberately no `else` branch.
        val chain = IssuerSignedJwt.readX5cChain(signed, LABEL)
        IssuerSignedJwt.validateChain(chain, now, LABEL)
        IssuerSignedJwt.verifySignature(signed, chain.first(), LABEL)

        // §3.5: "the Issuer of the Verifiable Credential is the subject of the end-entity
        // certificate". Where the credential also carries an `iss`, the two must agree, or
        // the certificate and the claim identify different issuers.
        issuerClaim(signed)?.let { iss ->
            check(iss == issuerId) { "$LABEL iss=$iss ≠ credential issuer identifier $issuerId" }
        }

        check(trustList.isIssuerTrusted(issuerId, chain)) {
            "$LABEL issuer $issuerId not trusted (or leaf fingerprint mismatch)"
        }
        return VerifiedIssuerBinding.X5c(chain)
    }

    /** §3.5 JWT VC Issuer Metadata. The key comes from the issuer's published JWK Set. */
    private suspend fun verifyByKeySet(
        signed: SignedJWT,
        issuerId: String,
        now: Instant,
    ): VerifiedIssuerBinding {
        // Presence is a rejection, and loudly: this is the shape a mechanism-confusion
        // attempt takes. Logged at WARN because it distinguishes a misconfigured issuer
        // from a deliberate probe, and neither is visible any other way.
        if (signed.header.x509CertChain != null) {
            Log.w(
                LOG_TAG,
                "mechanism confusion: credential from $issuerId carries x5c under jwt_vc_issuer_metadata policy",
            )
            error("$LABEL mechanism confusion: x5c present under jwt_vc_issuer_metadata policy for $issuerId")
        }

        // This mechanism "applies when the value of the `iss` claim ... is an HTTPS URI"
        // (§3.5), so an absent `iss` is not merely unhelpful — the mechanism does not apply.
        val iss = issuerClaim(signed) ?: error("$LABEL has no iss claim; jwt_vc_issuer_metadata requires one")
        check(iss == issuerId) { "$LABEL iss=$iss ≠ credential issuer identifier $issuerId" }

        val keySet = keySetResolver.resolve(issuerId, now).getOrThrow()

        val kid = signed.header.keyID
        val candidates = keySet.byKid(kid)
        check(candidates.isNotEmpty()) {
            "$LABEL kid=$kid names no key in the issuer key set from ${keySet.sourceUrl}"
        }

        val verifiedKey =
            candidates.firstOrNull { candidate ->
                runCatching {
                    IssuerSignedJwt.verifySignature(signed, publicKeyOf(candidate), LABEL)
                }.isSuccess
            } ?: error("$LABEL verified against no key in the issuer key set from ${keySet.sourceUrl}")

        check(trustList.isKeyTrusted(issuerId, verifiedKey)) {
            "$LABEL signing key is not a trusted key for $issuerId (jwk_thumbprints mismatch)"
        }

        return VerifiedIssuerBinding.KeySet(
            sourceUrl = keySet.sourceUrl,
            keyThumbprint = verifiedKey.computeThumbprint().toString(),
        )
    }

    private companion object {
        const val LOG_TAG = "CredSigVerifier"
        const val LABEL = "credential issuer JWT"

        /** The `iss` claim, or null when absent. Never throws on a malformed payload. */
        fun issuerClaim(signed: SignedJWT): String? = runCatching { signed.jwtClaimsSet.issuer }.getOrNull()?.takeIf { it.isNotBlank() }

        fun publicKeyOf(jwk: JWK): java.security.PublicKey =
            (jwk as? AsymmetricJWK)?.toPublicKey()
                ?: error("$LABEL key ${jwk.keyID} is not an asymmetric JWK")
    }
}
