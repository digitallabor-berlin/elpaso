package dev.digitallabor.elpaso.wallet.presentation.builder

import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.presentation.paso.PasoScaClaims
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import dev.digitallabor.elpaso.wallet.util.B64u
import dev.digitallabor.elpaso.wallet.util.JoseEcdsa
import java.security.MessageDigest
import java.security.Signature

/**
 * Builds the SD-JWT VC presentation: `<issuer-jwt>~<disclosure>~...~<KB-JWT>`.
 *
 * - Selects only the disclosures that match the DCQL-requested claim paths.
 * - Signs the Key Binding JWT with the credential's device key. The caller has already
 *   driven BiometricPrompt and hands us the authorised [Signature].
 * - If `transaction_data` entries are present, includes a `transaction_data_hashes` claim
 *   in the KB-JWT (per OpenID4VP §8) computed over the verbatim base64url entries.
 * - If [Input.pasoClaims] is set (i.e. the request involves a PaSO `transaction_data`
 *   entry), the SCA Response Claims from PaSO Core §6.1 are spliced into the payload as
 *   additional top-level claims, alongside the standard OID4VP claims.
 */
class SdJwtPresentationBuilder(
    private val keyManager: KeyManager,
) {
    data class Input(
        val rawSdJwt: String,
        val requestedClaimPaths: List<List<String>>,
        val nonce: String,
        val audience: String,
        val transactionData: List<TransactionData>,
        val deviceKeyAlias: String,
        val pasoClaims: PasoScaClaims? = null,
    )

    fun build(input: Input, authorizedSignature: Signature): String {
        val parts = input.rawSdJwt.split('~').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty SD-JWT" }
        val issuerJwt = parts.first()
        val allDisclosures = parts.drop(1)

        val selected = allDisclosures.filter { d -> matchesAnyPath(d, input.requestedClaimPaths) }

        val sdHashInput = buildString {
            append(issuerJwt)
            append('~')
            selected.forEach { append(it).append('~') }
        }
        val sdHash = sha256B64u(sdHashInput.toByteArray(Charsets.US_ASCII))

        val txDataHashes = input.transactionData.map { TransactionData.hashEntry(it.raw) }

        val headerJson = """{"alg":"ES256","typ":"kb+jwt"}"""
        val payloadJson = assemblePayloadJson(
            audience = input.audience,
            nonce = input.nonce,
            iatSeconds = System.currentTimeMillis() / 1000,
            sdHash = sdHash,
            transactionDataHashes = txDataHashes,
            pasoClaims = input.pasoClaims,
        )

        val signingInput = "${B64u.encode(headerJson)}.${B64u.encode(payloadJson)}"
        val derSignature = keyManager.signAuthorised(authorizedSignature, signingInput.toByteArray(Charsets.US_ASCII))
        val joseSignature = JoseEcdsa.derToJose(derSignature, partLen = 32)
        val kbJwt = "$signingInput.${B64u.encode(joseSignature)}"

        return buildString {
            append(issuerJwt)
            append('~')
            selected.forEach { append(it).append('~') }
            append(kbJwt)
        }
    }

    /**
     * A disclosure is base64url(JSON [salt, name, value]) or, for array elements, [salt, value].
     * We match by the claim name in the second slot. DCQL paths are JSON pointer paths, and
     * top-level claim names cover the common cases used by EUDI PID / mDL profiles.
     */
    private fun matchesAnyPath(disclosure: String, paths: List<List<String>>): Boolean {
        if (paths.isEmpty()) return true
        val decoded = runCatching { B64u.decode(disclosure).decodeToString() }.getOrElse { return false }
        val claimName = Regex(",\\s*\"([^\"]+)\"\\s*,").find(decoded)?.groupValues?.get(1)
            ?: return false
        return paths.any { it.firstOrNull() == claimName }
    }

    private fun sha256B64u(bytes: ByteArray): String =
        B64u.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal companion object {
        /**
         * Pure string assembly for the KB-JWT payload — extracted so it can be unit-tested
         * on the JVM without touching `android.util.Base64`. Splices the OID4VP
         * `transaction_data_hashes` array and, when present, the PaSO SCA Response Claims
         * from §6.1 into the payload alongside the standard `aud`/`nonce`/`iat`/`sd_hash`.
         */
        internal fun assemblePayloadJson(
            audience: String,
            nonce: String,
            iatSeconds: Long,
            sdHash: String,
            transactionDataHashes: List<String>,
            pasoClaims: PasoScaClaims?,
        ): String {
            val txDataClause =
                if (transactionDataHashes.isEmpty()) ""
                else ""","transaction_data_hashes_alg":"sha-256","transaction_data_hashes":[${
                    transactionDataHashes.joinToString(",") { "\"$it\"" }
                }]"""
            val pasoClause =
                if (pasoClaims == null) "" else "," + pasoClaims.toJsonFragment()
            return """{"aud":"$audience","nonce":"$nonce","iat":$iatSeconds,"sd_hash":"$sdHash"$txDataClause$pasoClause}"""
        }
    }
}
