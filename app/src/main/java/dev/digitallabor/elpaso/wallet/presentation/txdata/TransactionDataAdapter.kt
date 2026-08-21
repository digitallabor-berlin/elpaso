package dev.digitallabor.elpaso.wallet.presentation.txdata

import eu.europa.ec.eudi.openid4vp.TransactionData as LibTransactionData
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts the library's resolved [eu.europa.ec.eudi.openid4vp.TransactionData] into the
 * wallet's UI model.
 *
 * The library exposes `value` (the verbatim base64url string) and `json` (already decoded).
 * We map known `type` values to rich UI types ([TransactionData.PaymentData],
 * [TransactionData.QesAuthorization]); everything else falls back to [TransactionData.Generic]
 * so the consent screen can still display the raw fields.
 */
object TransactionDataAdapter {
    fun fromLibrary(lib: LibTransactionData): TransactionData {
        val raw = lib.value
        val obj = lib.json
        return when (lib.type.value) {
            TransactionData.PaymentData.TYPE -> TransactionData.PaymentData(
                raw = raw,
                payeeName = obj["payee_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["payee"]?.jsonPrimitive?.contentOrNull,
                payeeAccount = obj["payee_account"]?.jsonPrimitive?.contentOrNull
                    ?: obj["iban"]?.jsonPrimitive?.contentOrNull,
                amount = obj["amount"]?.jsonPrimitive?.contentOrNull,
                currency = obj["currency"]?.jsonPrimitive?.contentOrNull,
                reference = obj["reference"]?.jsonPrimitive?.contentOrNull,
            )
            TransactionData.QesAuthorization.TYPE -> TransactionData.QesAuthorization(
                raw = raw,
                documentDigests = obj["documentDigests"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["hash"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty(),
                hashAlg = obj["hashAlgorithmOID"]?.jsonPrimitive?.contentOrNull,
                signatureFormat = obj["signatureFormat"]?.jsonPrimitive?.contentOrNull,
                tosText = obj["terms"]?.jsonPrimitive?.contentOrNull,
            )
            TransactionData.PasoPayment.TYPE ->
                TransactionData.parsePasoPayment(raw, obj)
                    ?: TransactionData.Generic(raw = raw, type = lib.type.value, raw_obj = obj)
            else -> TransactionData.Generic(raw = raw, type = lib.type.value, raw_obj = obj)
        }
    }
}
