package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TransactionDataTest {

    private val b64u = Base64.getUrlEncoder().withoutPadding()

    /**
     * Per OpenID4VP §8, `transaction_data_hashes[i] = base64url(SHA-256(bytes(entry[i])))`
     * where `entry[i]` is the verbatim base64url-encoded string the verifier sent.
     */
    @Test
    fun `hashEntry produces a 43-char base64url SHA-256`() {
        val hash = TransactionData.hashEntry("aGVsbG8")
        assertEquals(43, hash.length)
        assertTrue(hash.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `parse PaymentData picks up payee and amount fields`() {
        val rawJson = """{"type":"payment_data","payee_name":"Acme","amount":"42.00","currency":"EUR"}"""
        val raw = b64u.encodeToString(rawJson.toByteArray(Charsets.UTF_8))
        val parsed = TransactionData.parse(raw)
        assertTrue(parsed is TransactionData.PaymentData)
        parsed as TransactionData.PaymentData
        assertEquals("Acme", parsed.payeeName)
        assertEquals("42.00", parsed.amount)
        assertEquals("EUR", parsed.currency)
        assertEquals(raw, parsed.raw)
    }
}
