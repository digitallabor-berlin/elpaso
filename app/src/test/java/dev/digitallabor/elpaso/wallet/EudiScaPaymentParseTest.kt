package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the `urn:eudi:sca:payment:1` parser directly over its decoded [JsonObject].
 *
 * Deliberately bypasses [TransactionData.parse], which calls `android.util.Base64` and
 * therefore returns null on the JVM (see AGENTS.md: `unitTests.isReturnDefaultValues`).
 * The base64 decode is not the logic under test — the field extraction is.
 */
class EudiScaPaymentParseTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /** The exact shape a verifier sends, per the EUDI SCA payment type. */
    private val wireEntry =
        """
        {
          "type": "urn:eudi:sca:payment:1",
          "credential_ids": ["dpc"],
          "transaction_data_hashes_alg": ["sha-256"],
          "payload": {
            "payee": { "name": "Rock Legends", "id": "Payee-id-123" },
            "transaction_id": "1234567890",
            "amount_display": "$ 592.68"
          }
        }
        """.trimIndent()

    @Test
    fun `parses payee, transaction id, amount and credential ids`() {
        val parsed = TransactionData.parseEudiScaPayment("RAW", obj(wireEntry))
        assertEquals("Rock Legends", parsed?.payeeName)
        assertEquals("Payee-id-123", parsed?.payeeId)
        assertEquals("1234567890", parsed?.transactionId)
        assertEquals("$ 592.68", parsed?.amountDisplay)
        assertEquals(listOf("dpc"), parsed?.credentialIds)
        assertEquals("RAW", parsed?.raw)
    }

    /** `amount_display` is a verifier-formatted string; the wallet must not reinterpret it. */
    @Test
    fun `amount display is preserved verbatim`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{
              "payee":{"name":"Acme"},"amount_display":"1.234,56 €"}}
            """.trimIndent()
        assertEquals("1.234,56 €", TransactionData.parseEudiScaPayment("R", obj(entry))?.amountDisplay)
    }

    @Test
    fun `type is the eudi sca payment urn and counts as SCA`() {
        val parsed = TransactionData.parseEudiScaPayment("R", obj(wireEntry))
        assertEquals("urn:eudi:sca:payment:1", parsed?.type)
        assertEquals(true, parsed?.isPaso())
    }

    @Test
    fun `optional fields are absent rather than blank`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{
              "payee":{"name":"Acme"},"amount_display":"$ 1.00"}}
            """.trimIndent()
        val parsed = TransactionData.parseEudiScaPayment("R", obj(entry))
        assertNull(parsed?.payeeId)
        assertNull(parsed?.transactionId)
        assertEquals(emptyList<String>(), parsed?.credentialIds)
    }

    /**
     * The DC API matcher accepts numeric `amount` + `currency` when `amount_display` is
     * absent, so the wallet must too — otherwise the system selector shows a payment the
     * wallet then rejects as malformed.
     */
    @Test
    fun `falls back to amount and currency when amount display is absent`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{
              "payee":{"name":"Acme"},"amount":"42.00","currency":"EUR"}}
            """.trimIndent()
        assertEquals("EUR 42.00", TransactionData.parseEudiScaPayment("R", obj(entry))?.amountDisplay)
    }

    @Test
    fun `returns null when no amount of any form is present`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{"payee":{"name":"Acme"}}}
            """.trimIndent()
        assertNull(TransactionData.parseEudiScaPayment("R", obj(entry)))
    }

    @Test
    fun `returns null when payee name is missing`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{
              "payee":{"id":"p-1"},"amount_display":"$ 1.00"}}
            """.trimIndent()
        assertNull(TransactionData.parseEudiScaPayment("R", obj(entry)))
    }

    @Test
    fun `returns null when payee name is blank`() {
        val entry =
            """
            {"type":"urn:eudi:sca:payment:1","payload":{
              "payee":{"name":"   "},"amount_display":"$ 1.00"}}
            """.trimIndent()
        assertNull(TransactionData.parseEudiScaPayment("R", obj(entry)))
    }

    @Test
    fun `returns null when the payload object is absent`() {
        val entry = """{"type":"urn:eudi:sca:payment:1","amount_display":"$ 1.00"}"""
        assertNull(TransactionData.parseEudiScaPayment("R", obj(entry)))
    }
}
