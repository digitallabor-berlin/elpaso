package dev.digitallabor.elpaso.wallet.issuance.proofs

import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.session.BiometricSignAuthorizer
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import eu.europa.ec.eudi.openid4vci.BatchSigner
import eu.europa.ec.eudi.openid4vci.BatchSignOperation
import eu.europa.ec.eudi.openid4vci.JwtBindingKey
import eu.europa.ec.eudi.openid4vci.SignOperation
import java.security.interfaces.ECPublicKey

/**
 * Bridges [KeyManager]'s Android Keystore-backed P-256 keys into the EUDI VCI library's
 * [BatchSigner] / [SignOperation] contract.
 *
 * Device keys are generated with `setUserAuthenticationRequired(true)` and per-use auth
 * (`timeout = 0`), so each individual `Signature.sign(...)` needs an authorisation token
 * issued by a `BiometricPrompt.CryptoObject` immediately before it runs. The injected
 * [BiometricSignAuthorizer] suspends until the user authenticates, then returns a Signature
 * good for exactly one signing call.
 */
class WalletProofsSigner(
    private val keyManager: KeyManager,
    private val keyAliases: List<String>,
    private val authorizer: BiometricSignAuthorizer,
) : BatchSigner<JwtBindingKey> {

    override val javaAlgorithm: String = "SHA256withECDSA"

    override suspend fun authenticate(): BatchSignOperation<JwtBindingKey> {
        val operations = keyAliases.map { alias ->
            val pub = keyManager.publicKey(alias)
            val jwk = ECKey.Builder(Curve.P_256, pub as ECPublicKey).build()
            SignOperation(
                // Return raw DER — the library transcodes to JOSE R||S itself via
                // SigningOps.transcodeSignatureToConcat. Double-transcoding produces
                // a 64-byte value the library then misreads as DER.
                function = { input ->
                    val authorized = authorizer.authorize(alias)
                    keyManager.signAuthorised(authorized, input)
                },
                publicMaterial = JwtBindingKey.Jwk(jwk),
            )
        }
        return BatchSignOperation(operations)
    }

    override suspend fun release(signOps: BatchSignOperation<JwtBindingKey>?) {
        // Keystore-backed keys hold no per-invocation resources to clean up.
    }
}
