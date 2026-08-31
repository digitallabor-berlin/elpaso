package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.testing.TestPki
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-issuer Issuer Signature Mechanism policy — draft-ietf-oauth-sd-jwt-vc-11
 * §10.2's "an attacker cannot influence the type of verification method". Exercised
 * over the pure parse and match helpers so no `Context` or asset is needed.
 */
class TrustListPolicyTest {
    private fun file(entries: String) = """{ "version": 1, "issuers": [$entries] }"""

    private val x5cEntry =
        """
        { "id": "https://x5c.example", "label": "X5c Issuer", "signature_mechanism": "x5c" }
        """.trimIndent()

    private val keySetEntry =
        """
        {
          "id": "https://keyset.example",
          "label": "Key-set Issuer",
          "signature_mechanism": "jwt_vc_issuer_metadata",
          "jwk_thumbprints": ["THUMB-1"]
        }
        """.trimIndent()

    @Test
    fun `parses both mechanisms`() {
        val issuers = TrustListService.parseIssuers(file("$x5cEntry, $keySetEntry"))
        assertEquals(2, issuers.size)
        assertEquals(SignatureMechanism.X5c, issuers[0].signature_mechanism)
        assertEquals(SignatureMechanism.JwtVcIssuerMetadata, issuers[1].signature_mechanism)
        assertEquals(listOf("THUMB-1"), issuers[1].jwk_thumbprints)
    }

    @Test
    fun `an entry without signature_mechanism fails to parse`() {
        val entry = """{ "id": "https://x.example", "label": "No Mechanism" }"""
        try {
            TrustListService.parseIssuers(file(entry))
            org.junit.Assert.fail("expected SerializationException — a missing mechanism is not a default")
        } catch (e: SerializationException) {
            assertTrue(e.message!!.contains("signature_mechanism"))
        }
    }

    @Test
    fun `an unknown mechanism value fails to parse`() {
        val entry = """{ "id": "https://x.example", "label": "Bogus", "signature_mechanism": "did" }"""
        try {
            TrustListService.parseIssuers(file(entry))
            org.junit.Assert.fail("expected SerializationException")
        } catch (e: SerializationException) {
            assertTrue(true)
        }
    }

    @Test
    fun `jwk_thumbprints defaults to empty and then trusts the whole published set`() {
        val issuers = TrustListService.parseIssuers(file(x5cEntry))
        assertTrue(issuers[0].jwk_thumbprints.isEmpty())
        assertTrue(TrustListService.keyTrusted(issuers[0], "ANY-THUMBPRINT"))
    }

    @Test
    fun `a pinned thumbprint set admits only its members`() {
        val entry = TrustListService.parseIssuers(file(keySetEntry)).single()
        assertTrue(TrustListService.keyTrusted(entry, "THUMB-1"))
        assertFalse(TrustListService.keyTrusted(entry, "THUMB-2"))
    }

    @Test
    fun `thumbprint matching is case-sensitive because base64url is`() {
        val entry = TrustListService.parseIssuers(file(keySetEntry)).single()
        assertFalse(TrustListService.keyTrusted(entry, "thumb-1"))
    }

    @Test
    fun `an RFC 7638 thumbprint of a real JWK matches when pinned`() {
        val node = TestPki.ca("CN=Thumbprint Subject")
        val jwk = TestPki.jwk(node, kid = "k1")
        val thumbprint = jwk.computeThumbprint().toString()
        val entry =
            TrustListService
                .parseIssuers(
                    file(
                        """
                        {
                          "id": "https://keyset.example",
                          "label": "Key-set Issuer",
                          "signature_mechanism": "jwt_vc_issuer_metadata",
                          "jwk_thumbprints": ["$thumbprint"]
                        }
                        """.trimIndent(),
                    ),
                ).single()
        assertTrue(TrustListService.keyTrusted(entry, thumbprint))
    }
}
