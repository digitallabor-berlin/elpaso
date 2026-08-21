package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DcIssuanceRequestTest {
    private fun request(
        protocol: String = "openid4vci1.0",
        grants: String = """
            {"urn:ietf:params:oauth:grant-type:pre-authorized_code":{"pre-authorized_code":"abc"}}
        """,
        inlineIssuer: String? = "https://issuer.example",
    ): String {
        val metadata =
            inlineIssuer?.let {
                ""","credential_issuer_metadata":{"credential_issuer":"$it","extra":1}"""
            } ?: ""
        return """
        {"requests":[{"protocol":"$protocol","data":{
          "credential_issuer":"https://issuer.example",
          "credential_configuration_ids":["pid"],
          "grants":$grants
          $metadata
        }}]}
        """
    }

    private fun decodedOffer(result: DcIssuanceRequest.Result): Map<String, *> {
        val offer = result as DcIssuanceRequest.Result.Offer
        val encoded = offer.offerUri.substringAfter("credential_offer=")
        return Json.parseToJsonElement(URLDecoder.decode(encoded, "UTF-8")).jsonObject
    }

    @Test
    fun `accepts all three supported protocol identifiers`() {
        listOf("openid4vci1.0", "openid4vci-v1", "openid4vci").forEach { protocol ->
            val result = DcIssuanceRequest.map(request(protocol = protocol))
            assertTrue("$protocol should be accepted", result is DcIssuanceRequest.Result.Offer)
        }
    }

    @Test
    fun `rejects an unsupported protocol`() {
        val result = DcIssuanceRequest.map(request(protocol = "openid4vp"))
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `produces an openid-credential-offer uri carrying the issuer`() {
        val result = DcIssuanceRequest.map(request())
        val offer = result as DcIssuanceRequest.Result.Offer
        assertEquals("https://issuer.example", offer.issuerId)
        assertTrue(offer.offerUri.startsWith("openid-credential-offer://?credential_offer="))
    }

    @Test
    fun `strips dc api only keys but keeps the spec offer fields`() {
        val offer = decodedOffer(DcIssuanceRequest.map(request()))
        assertTrue("credential_issuer_metadata" !in offer.keys)
        assertTrue("authorization_server_metadata" !in offer.keys)
        assertTrue("credential_issuer" in offer.keys)
        assertTrue("credential_configuration_ids" in offer.keys)
        assertTrue("grants" in offer.keys)
    }

    @Test
    fun `rejects when inline metadata issuer disagrees with the offer issuer`() {
        val result = DcIssuanceRequest.map(request(inlineIssuer = "https://evil.example"))
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `accepts when inline metadata is absent entirely`() {
        val result = DcIssuanceRequest.map(request(inlineIssuer = null))
        assertTrue(result is DcIssuanceRequest.Result.Offer)
    }

    @Test
    fun `rejects an authorization code only offer`() {
        val result =
            DcIssuanceRequest.map(
                request(grants = """{"authorization_code":{"issuer_state":"xyz"}}"""),
            )
        assertTrue(result is DcIssuanceRequest.Result.Rejected)
    }

    @Test
    fun `rejects malformed json`() {
        assertTrue(DcIssuanceRequest.map("not json") is DcIssuanceRequest.Result.Rejected)
        assertTrue(DcIssuanceRequest.map("""{"nope":1}""") is DcIssuanceRequest.Result.Rejected)
    }
}
