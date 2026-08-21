package dev.digitallabor.elpaso.wallet.presentation.txdata

import android.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single `transaction_data` entry from an OpenID4VP presentation request.
 *
 * The wire entries are base64url(JSON). We keep the original bytes around verbatim so we
 * can hash exactly what the verifier sent — re-serializing the JSON would risk producing
 * different bytes (key order, whitespace, number formatting) and break the binding.
 */
sealed interface TransactionData {

    /** The verbatim base64url string the verifier supplied. Hashed into the response. */
    val raw: String

    /** Parsed `type` field. */
    val type: String

    /**
     * True iff this entry is PaSO-targeted (PaSO Core §5.2 — `type` URN prefix). The SCA
     * Response Claims (§6.1) get spliced into the KB-JWT only when at least one entry on
     * the request is PaSO.
     */
    fun isPaso(): Boolean = type.startsWith("urn:paso:sca:")

    /**
     * The transaction_data scope used by `claims` metadata paths in
     * `transaction_data_types` (paso-proof-metadata.md §3.1: "the `path` parameter
     * resolves against the `transaction_data` `payload` object"). When the entry has
     * a nested `payload` object (PaSO), that's the scope; otherwise the full object
     * (minus `type`) is used.
     */
    val payloadScope: JsonObject? get() = decodePayloadScope(raw)

    data class PaymentData(
        override val raw: String,
        val payeeName: String?,
        val payeeAccount: String?,
        val amount: String?,
        val currency: String?,
        val reference: String?,
    ) : TransactionData {
        override val type: String get() = TYPE
        companion object { const val TYPE = "payment_data" }
    }

    data class QesAuthorization(
        override val raw: String,
        val documentDigests: List<String>,
        val hashAlg: String?,
        val signatureFormat: String?,
        val tosText: String?,
    ) : TransactionData {
        override val type: String get() = TYPE
        companion object { const val TYPE = "qcert_creation_acceptance" }
    }

    data class PasoPayment(
        override val raw: String,
        val transactionId: String?,
        val payeeName: String,
        val payeeId: String,
        val amount: String,
        val currency: String,
        val amountRaw: String,
    ) : TransactionData {
        override val type: String get() = TYPE
        companion object { const val TYPE = "urn:paso:sca:global:payment:1" }
    }

    /** A transaction_data entry whose type is known but whose required fields are missing or malformed. */
    data class Invalid(
        override val raw: String,
        override val type: String,
    ) : TransactionData

    data class Generic(
        override val raw: String,
        override val type: String,
        val raw_obj: JsonObject,
    ) : TransactionData

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseAll(rawEntries: List<String>): List<TransactionData> = rawEntries.map(::parse)

        fun parse(raw: String): TransactionData {
            val decoded = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val obj = json.parseToJsonElement(decoded.decodeToString()).jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
                ?: error("transaction_data entry missing 'type'")
            return when (type) {
                PaymentData.TYPE -> PaymentData(
                    raw = raw,
                    payeeName = obj["payee_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["payee"]?.jsonPrimitive?.contentOrNull,
                    payeeAccount = obj["payee_account"]?.jsonPrimitive?.contentOrNull
                        ?: obj["iban"]?.jsonPrimitive?.contentOrNull,
                    amount = obj["amount"]?.jsonPrimitive?.contentOrNull,
                    currency = obj["currency"]?.jsonPrimitive?.contentOrNull,
                    reference = obj["reference"]?.jsonPrimitive?.contentOrNull,
                )
                QesAuthorization.TYPE -> QesAuthorization(
                    raw = raw,
                    documentDigests = obj["documentDigests"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["hash"]?.jsonPrimitive?.contentOrNull }
                        .orEmpty(),
                    hashAlg = obj["hashAlgorithmOID"]?.jsonPrimitive?.contentOrNull,
                    signatureFormat = obj["signatureFormat"]?.jsonPrimitive?.contentOrNull,
                    tosText = obj["terms"]?.jsonPrimitive?.contentOrNull,
                )
                PasoPayment.TYPE -> parsePasoPayment(raw, obj) ?: Invalid(raw = raw, type = type)
                else -> Generic(raw = raw, type = type, raw_obj = obj)
            }
        }

        /**
         * Extracts a [PasoPayment] from the nested `payload` object. Returns null when any
         * required field is absent so the caller can fall back to [Generic] rather than crash.
         */
        internal fun parsePasoPayment(raw: String, obj: JsonObject): PasoPayment? {
            val payload = obj["payload"]?.jsonObject ?: return null
            val transactionId = payload["transaction_id"]?.jsonPrimitive?.contentOrNull
            val payee = payload["payee"]?.jsonObject ?: return null
            val payeeName = payee["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val payeeId = payee["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val amountRaw = payload["amount"]?.jsonPrimitive?.contentOrNull ?: return null
            val (amount, currency) = splitIsoCurrencyAmount(amountRaw)
            return PasoPayment(
                raw = raw,
                transactionId = transactionId,
                payeeName = payeeName,
                payeeId = payeeId,
                amount = amount,
                currency = currency,
                amountRaw = amountRaw,
            )
        }

        private fun splitIsoCurrencyAmount(value: String): Pair<String, String> {
            val idx = value.lastIndexOf(' ')
            if (idx <= 0 || idx == value.length - 1) return value to ""
            return value.substring(0, idx) to value.substring(idx + 1)
        }

        /**
         * Per OpenID4VP §8, each `transaction_data_hashes` entry is
         * `base64url(SHA-256(transaction_data_string_bytes))` where the string is the
         * verbatim base64url entry the verifier sent.
         */
        fun hashEntry(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        internal fun decodePayloadScope(raw: String): JsonObject? = runCatching {
            val decoded = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val obj = json.parseToJsonElement(decoded.decodeToString()).jsonObject
            val nested = obj["payload"]
            if (nested != null && nested is JsonObject) nested else JsonObject(obj.filterKeys { it != "type" })
        }.getOrNull()
    }
}
