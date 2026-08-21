package dev.digitallabor.elpaso.wallet.domain.model

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the JWT-payload → domain-model decoding mirrors the Annex A example in
 * paso-proof-metadata.md.
 */
class CredentialMetadataPayloadTest {

    @Test
    fun decodesSpecAnnexAExample() {
        val payloadDto = HttpClientFactory.json.decodeFromString(
            CredentialMetadataPayloadDto.serializer(),
            ANNEX_A_PAYLOAD,
        )
        val metadata = payloadDto.toDomain()

        assertEquals("https://issuer.bank.example", metadata.iss)
        assertEquals("https://bank.example/sca/card", metadata.sub)
        assertEquals("dc+sd-jwt", metadata.format)
        assertEquals(1710086400L, metadata.exp)

        // top-level display: two locales
        assertEquals(2, metadata.display.size)
        assertNotNull(metadata.display.firstOrNull { it.locale == "en" })
        assertNotNull(metadata.display.firstOrNull { it.locale == "de" })

        // transaction_data_types: PaSO global payment v1
        val tdt = metadata.transactionDataTypes["urn:paso:sca:global:payment:1"]
        assertNotNull(tdt)
        assertEquals(4, tdt!!.claims.size)
        val amount = tdt.claims.first { it.path == listOf("amount") }
        assertEquals("iso_currency_amount", amount.valueType)
        assertTrue(amount.mandatory)
        // claims without display (transaction_id, payee.id) are internal
        val internalOnly = tdt.claims.filter { it.display.isEmpty() }
        assertEquals(2, internalOnly.size)
    }

    private companion object {
        // Verbatim Annex A.2 payload from paso-proof-metadata.md
        const val ANNEX_A_PAYLOAD = """
        {
          "iss": "https://issuer.bank.example",
          "sub": "https://bank.example/sca/card",
          "format": "dc+sd-jwt",
          "iat": 1710000000,
          "exp": 1710086400,
          "credential_metadata_uri": "https://issuer.bank.example/credential-metadata/BankPaymentCard",
          "credential_metadata": {
            "display": [
              { "name": "Bank Payment Card", "locale": "en",
                "logo": { "uri": "https://issuer.bank.example/logo.png", "alt_text": "Bank logo" } },
              { "name": "Bank Zahlungskarte", "locale": "de",
                "logo": { "uri": "https://issuer.bank.example/logo.png", "alt_text": "Bank-Logo" } }
            ],
            "transaction_data_types": {
              "urn:paso:sca:global:payment:1": {
                "claims": [
                  { "path": ["transaction_id"], "mandatory": true },
                  { "path": ["amount"], "mandatory": true, "value_type": "iso_currency_amount",
                    "display": [
                      { "locale": "en", "name": "Amount" },
                      { "locale": "de", "name": "Betrag" }
                    ] },
                  { "path": ["payee", "name"], "mandatory": true,
                    "display": [
                      { "locale": "en", "name": "Payee" },
                      { "locale": "de", "name": "Empfänger" }
                    ] },
                  { "path": ["payee", "id"], "mandatory": true }
                ]
              }
            }
          }
        }
        """
    }
}
