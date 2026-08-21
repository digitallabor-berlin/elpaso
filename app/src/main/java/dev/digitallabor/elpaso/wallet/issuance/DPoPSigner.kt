package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import eu.europa.ec.eudi.openid4vci.SignOperation
import eu.europa.ec.eudi.openid4vci.Signer
import java.security.interfaces.ECPublicKey

/**
 * Signer for DPoP proof JWTs (RFC 9449). Wraps the long-lived, non-auth-gated EC P-256
 * key managed by [KeyManager] (`dpop_v1`). The EUDI VCI library calls [acquire] once per
 * token-endpoint and credential-endpoint request to mint a fresh proof JWT, so signing
 * must succeed without any user interaction — that's why the underlying keystore entry
 * is created with `setUserAuthenticationRequired(false)`.
 *
 * The `function` returns the **DER** signature straight from `SHA256withECDSA`; the
 * library's `DefaultJwtSigner` does the DER→JOSE concat transcoding itself.
 */
class DPoPSigner(private val keyManager: KeyManager) : Signer<JWK> {

    override val javaAlgorithm: String = KeyManager.SIGNATURE_ALGORITHM

    override suspend fun acquire(): SignOperation<JWK> {
        val pub = keyManager.getOrCreateDPoPKey() as ECPublicKey
        val jwk: JWK = ECKey.Builder(Curve.P_256, pub).build()
        return SignOperation(
            function = { input -> keyManager.signSilently(KeyManager.DPOP_KEY_ALIAS, input) },
            publicMaterial = jwk,
        )
    }

    override suspend fun release(signOperation: SignOperation<JWK>?) {
        // Nothing to release — the keystore entry is long-lived.
    }
}
