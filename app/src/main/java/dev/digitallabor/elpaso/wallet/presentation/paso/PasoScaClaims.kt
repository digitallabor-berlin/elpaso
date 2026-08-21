package dev.digitallabor.elpaso.wallet.presentation.paso

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * SCA Response Claims from PaSO Core §6.1, included as top-level KB-JWT payload claims
 * whenever the presentation involves a PaSO-targeted `transaction_data` entry. Per §6.3
 * these are added alongside the standard OID4VP KB-JWT claims, not as a replacement.
 *
 * `metadataIntegrity` is null when no signed credential metadata JWT was used — §6.1
 * specifies the claim is omitted in that case.
 */
data class PasoScaClaims(
    val jti: String,
    val responseMode: String,
    val displayLocale: String,
    val amr: List<String>,
    val transactionDataHash: String,
    val transactionDataHashAlg: String,
    val metadataIntegrity: String?,
    val requestIntegrity: String,
    val walletInstanceVersion: String,
) {
    /**
     * Emits the SCA claims as a JSON object body **without** surrounding braces, ready to
     * be appended (with a leading comma) to an existing JWT payload JSON literal. Uses
     * kotlinx.serialization to handle escaping rather than hand-rolling it.
     */
    fun toJsonFragment(): String {
        val obj = buildJsonObject {
            put("jti", jti)
            put("response_mode", responseMode)
            put("display_locale", displayLocale)
            putJsonArray("amr") { amr.forEach { add(it) } }
            put("transaction_data_hash", transactionDataHash)
            put("transaction_data_hash_alg", transactionDataHashAlg)
            metadataIntegrity?.let { put("metadata_integrity", it) }
            put("request_integrity", requestIntegrity)
            put("wallet_instance_version", walletInstanceVersion)
        }
        val rendered = Json.encodeToString(JsonObject.serializer(), obj)
        return rendered.removeSurrounding("{", "}")
    }
}
