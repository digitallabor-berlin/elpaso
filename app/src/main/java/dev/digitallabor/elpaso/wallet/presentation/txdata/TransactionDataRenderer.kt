package dev.digitallabor.elpaso.wallet.presentation.txdata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.digitallabor.elpaso.wallet.R
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@Composable
fun TransactionDataBlock(
    item: TransactionData,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (item) {
                is TransactionData.PaymentData -> {
                    Text(stringResource(R.string.tx_data_payment_heading), style = MaterialTheme.typography.labelLarge)
                    val amountLine = listOfNotNull(item.currency, item.amount)
                        .joinToString(" ").ifBlank { stringResource(R.string.tx_data_amount_missing) }
                    Text(amountLine, style = MaterialTheme.typography.displaySmall)
                    item.payeeName?.let {
                        Text(stringResource(R.string.tx_data_payee, it), style = MaterialTheme.typography.titleMedium)
                    }
                    item.payeeAccount?.let {
                        Text(stringResource(R.string.tx_data_account, maskAccount(it)), style = MaterialTheme.typography.bodyMedium)
                    }
                    item.reference?.let {
                        Text(stringResource(R.string.tx_data_reference, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is TransactionData.PasoPayment -> {
                    Text(stringResource(R.string.tx_data_payment_heading), style = MaterialTheme.typography.labelLarge)
                    Text(formatIsoCurrencyAmount(item.amount, item.currency, item.amountRaw), style = MaterialTheme.typography.displaySmall)
                    Text(stringResource(R.string.tx_data_payee, item.payeeName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tx_data_payee_id, item.payeeId), style = MaterialTheme.typography.bodySmall)
                    item.transactionId?.let {
                        Text(stringResource(R.string.tx_data_reference, it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is TransactionData.QesAuthorization -> {
                    Text(stringResource(R.string.tx_data_qes_heading), style = MaterialTheme.typography.labelLarge)
                    item.signatureFormat?.let {
                        Text(stringResource(R.string.tx_data_qes_format, it), style = MaterialTheme.typography.bodyMedium)
                    }
                    val count = item.documentDigests.size
                    Text(
                        pluralStringResource(R.plurals.tx_data_qes_document_count, count, count),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    item.tosText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                is TransactionData.Invalid -> {
                    Text(stringResource(R.string.tx_data_invalid_heading), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.tx_data_invalid_body), style = MaterialTheme.typography.bodyMedium)
                }
                is TransactionData.Generic -> GenericPayloadForm(item)
            }
        }
    }
}

/**
 * Best-effort form rendering when the wallet has NO signed credential metadata for
 * a transaction_data type. Walks the payload (or top-level object minus `type`) and
 * shows each leaf as `key: value`. Nested objects render as indented sub-sections.
 *
 * This is the "no metadata" fallback — if the credential carries verified metadata,
 * [dev.digitallabor.elpaso.wallet.presentation.txdata.DynamicTransactionDataBlock] is used
 * instead and renders per the PaSO View spec.
 */
@Composable
private fun GenericPayloadForm(item: TransactionData.Generic) {
    Text(stringResource(R.string.tx_data_generic_heading, item.type), style = MaterialTheme.typography.titleMedium)
    val payload = item.payloadScope ?: kotlinx.serialization.json.JsonObject(item.raw_obj.filterKeys { it != "type" })
    PayloadEntries(payload, depth = 0)
}

@Composable
private fun PayloadEntries(obj: kotlinx.serialization.json.JsonObject, depth: Int) {
    val indent = (depth * 12).dp
    obj.forEach { (key, value) ->
        when (value) {
            is kotlinx.serialization.json.JsonObject -> {
                Text(
                    text = humaniseKey(key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = indent, top = 4.dp),
                )
                PayloadEntries(value, depth + 1)
            }
            else -> Text(
                text = "${humaniseKey(key)}: ${stringifyLeaf(value)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = indent),
            )
        }
    }
}

private fun humaniseKey(key: String): String = key
    .replace('_', ' ')
    .replaceFirstChar { it.uppercaseChar() }

private fun stringifyLeaf(value: kotlinx.serialization.json.JsonElement): String = when (value) {
    is kotlinx.serialization.json.JsonPrimitive -> value.content
    is kotlinx.serialization.json.JsonArray -> value.joinToString(", ") { stringifyLeaf(it) }
    else -> value.toString()
}

private fun maskAccount(raw: String): String {
    if (raw.length <= 6) return raw
    return raw.take(4) + "****" + raw.takeLast(4)
}

private fun formatIsoCurrencyAmount(amount: String, currency: String, fallback: String): String {
    if (currency.isBlank()) return fallback
    return try {
        val value = amount.toBigDecimal()
        NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            this.currency = Currency.getInstance(currency)
        }.format(value)
    } catch (_: NumberFormatException) {
        fallback
    } catch (_: IllegalArgumentException) {
        fallback
    }
}
