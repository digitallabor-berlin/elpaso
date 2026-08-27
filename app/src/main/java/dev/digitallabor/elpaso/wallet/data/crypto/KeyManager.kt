package dev.digitallabor.elpaso.wallet.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Generates and uses per-credential P-256 keypairs stored in the Android Keystore.
 *
 * Keys are bound to user authentication (StrongBox-backed where the device supports it). The
 * Signature object returned by [signatureForAuth] must be authorised through BiometricPrompt
 * before [signAuthorised] is called, so the caller drives the BiometricPrompt flow.
 */
class KeyManager {
    fun createDeviceKey(alias: String): PublicKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) {
            return ks.getCertificate(alias).publicKey
        }
        return generate(alias, requireAuth = true, requireStrongBox = true)
    }

    /**
     * Long-lived, non-auth-gated EC P-256 key used to sign DPoP proof JWTs. Created lazily
     * on first use. Must not require biometric auth: a fresh DPoP proof is minted on every
     * outbound protected request and prompting the user each time is unworkable.
     */
    fun getOrCreateDPoPKey(): PublicKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(DPOP_KEY_ALIAS)) {
            return ks.getCertificate(DPOP_KEY_ALIAS).publicKey
        }
        return generate(DPOP_KEY_ALIAS, requireAuth = false, requireStrongBox = true)
    }

    /**
     * Synchronous, non-interactive signature for keys created with `requireAuth = false`
     * (currently only the DPoP key). Throws if invoked with a key that's auth-gated.
     */
    fun signSilently(
        alias: String,
        data: ByteArray,
    ): ByteArray {
        val pk = privateKey(alias)
        val signature =
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(pk)
                update(data)
            }
        return signature.sign()
    }

    private fun generate(
        alias: String,
        requireAuth: Boolean,
        requireStrongBox: Boolean,
    ): PublicKey {
        val builder =
            KeyGenParameterSpec
                .Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(requireAuth)

        if (requireAuth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Time-bound auth: any BIOMETRIC_STRONG authentication in the last
            // AUTH_VALIDITY_SECONDS authorises this key. That is what lets a single prompt
            // cover a whole `credential_sets` presentation — per-use auth (timeout 0)
            // authorises exactly one Signature, so an N-credential response cost N scans.
            //
            // The trade-off, deliberately accepted: the biometric is no longer
            // cryptographically bound to each individual signature via CryptoObject, only
            // to a recent strong authentication. The `bio_strong` AMR claim stays truthful.
            //
            // `Signature.initSign(privateKey)` throws UserNotAuthenticatedException for
            // these keys until an auth has happened, so the CryptoObject cannot be built
            // up front — BiometricAuthorizer runs a CryptoObject-less prompt first and
            // initialises the Signature afterwards.
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && requireStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        return try {
            kpg.initialize(builder.build())
            kpg.generateKeyPair().public
        } catch (_: StrongBoxUnavailableException) {
            if (requireStrongBox) {
                generate(alias, requireAuth, requireStrongBox = false)
            } else {
                throw IllegalStateException("Failed to generate key '$alias' without StrongBox")
            }
        }
    }

    fun deleteDeviceKey(alias: String) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    fun publicKey(alias: String): ECPublicKey = keyStore().getCertificate(alias).publicKey as ECPublicKey

    fun keyPair(alias: String): KeyPair {
        val ks = keyStore()
        val entry = ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    fun privateKey(alias: String): PrivateKey = (keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    /**
     * Returns an initialized [Signature] for the given alias. Wrap this in
     * `BiometricPrompt.CryptoObject` and call [signAuthorised] after the user authenticates.
     */
    fun signatureForAuth(alias: String): Signature {
        val pk = privateKey(alias)
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(pk) }
    }

    /**
     * Whether [alias] accepts a recent BIOMETRIC_STRONG auth (time-bound) rather than
     * demanding a CryptoObject per signature (per-use).
     *
     * Keystore auth parameters are fixed at generation time and cannot be migrated, so
     * credentials issued before the switch to time-bound keys stay per-use for their whole
     * life. Callers must therefore branch per key rather than assume a single policy —
     * see [dev.digitallabor.elpaso.wallet.session.planBiometricAuth].
     */
    fun usesTimeBoundAuth(alias: String): Boolean {
        val pk = privateKey(alias)
        val info =
            KeyFactory
                .getInstance(pk.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(pk, KeyInfo::class.java)
        // -1 (or 0) means "authenticate for every use"; a positive value is the validity
        // window in seconds.
        return info.isUserAuthenticationRequired && info.userAuthenticationValidityDurationSeconds > 0
    }

    fun signAuthorised(
        signature: Signature,
        data: ByteArray,
    ): ByteArray {
        signature.update(data)
        return signature.sign()
    }

    fun strongBiometricAvailable(bm: BiometricManager): Boolean =
        bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val EC_CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val DPOP_KEY_ALIAS = "dpop_v1"

        /**
         * Window during which a device key stays usable after a successful
         * BIOMETRIC_STRONG auth elsewhere on the device (e.g. Play Services' Credential
         * Manager prompt that precedes a DC API presentation). Picked to comfortably
         * cover the matcher → activity → biometric → sign hop without leaving a long
         * tail of "key unlocked even though I navigated away" time.
         */
        const val AUTH_VALIDITY_SECONDS = 30
    }
}
