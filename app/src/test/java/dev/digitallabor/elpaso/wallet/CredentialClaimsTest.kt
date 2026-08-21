package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.domain.claims.CredentialClaims
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class CredentialClaimsTest {

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun cred(format: Format, payload: ByteArray): Credential = Credential(
        id = "id",
        format = format,
        configurationId = "cfg",
        issuerId = "https://issuer.example",
        displayName = "name",
        displayMetadataJson = "{}",
        payload = payload,
        deviceKeyAlias = "k",
        issuedAt = Instant.EPOCH,
        expiresAt = null,
        lastUsedAt = null,
        usageCount = 0,
    )

    private fun b64u(json: String): String =
        urlEncoder.encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun b64u(bytes: ByteArray): String =
        urlEncoder.encodeToString(bytes)

    private fun sdJwt(payloadJson: String, vararg disclosureJsons: String): ByteArray {
        val header = b64u("""{"alg":"ES256","typ":"vc+sd-jwt"}""")
        val payload = b64u(payloadJson)
        val signature = b64u("sig")
        val issuerJwt = "$header.$payload.$signature"
        val disclosures = disclosureJsons.joinToString("") { "~${b64u(it)}" }
        return ("$issuerJwt$disclosures~").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `mdoc credential parses namespaces and elements into user bucket`() {
        val aliceDataItem = org.multipaz.cbor.Tstr("Alice")
        val ageDataItem = org.multipaz.cbor.CborArray.builder().add(30L).end().build().asArray[0]
        
        val issuerSignedItemsCbor = buildMdocPayload(
            namespaces = mapOf(
                "org.iso.18013.5.1" to mapOf(
                    "given_name" to org.multipaz.cbor.Cbor.encode(aliceDataItem),
                    "age" to org.multipaz.cbor.Cbor.encode(ageDataItem)
                )
            )
        )
        val payload = b64u(issuerSignedItemsCbor).toByteArray(Charsets.UTF_8)
        val extracted = CredentialClaims.extract(cred(Format.MsoMdoc, payload))
        
        assertEquals("Alice", extracted.user["given_name"]!!.jsonPrimitive.content)
        assertEquals("30", extracted.user["age"]!!.jsonPrimitive.content)
        assertEquals("cfg", extracted.protocol["docType"]!!.jsonPrimitive.content)
    }

    private fun buildMdocPayload(namespaces: Map<String, Map<String, ByteArray>>): ByteArray {
        val nameSpacesBuilder = org.multipaz.cbor.CborMap.builder()
        for ((ns, elements) in namespaces) {
            val nsArrayBuilder = org.multipaz.cbor.CborArray.builder()
            for ((identifier, valueBytes) in elements) {
                val item = org.multipaz.mdoc.issuersigned.IssuerSignedItem(
                    digestId = 0L,
                    random = kotlinx.io.bytestring.ByteString(byteArrayOf(1, 2, 3)),
                    dataElementIdentifier = identifier,
                    dataElementValue = org.multipaz.cbor.Cbor.decode(valueBytes)
                )
                nsArrayBuilder.add(org.multipaz.cbor.Tagged(24L, org.multipaz.cbor.Bstr(org.multipaz.cbor.Cbor.encode(item.toDataItem()))))
            }
            nameSpacesBuilder.put(ns, nsArrayBuilder.end().build())
        }
        
        val issuerSignedMap = org.multipaz.cbor.CborMap.builder()
            .put("nameSpaces", nameSpacesBuilder.end().build())
            .put("issuerAuth", org.multipaz.cbor.CborArray.builder().end().build()) // Dummy
            .end()
            .build()
            
        return org.multipaz.cbor.Cbor.encode(issuerSignedMap)
    }
    
    @Test
    fun `malformed mdoc credential does not crash`() {
        val extracted = CredentialClaims.extract(cred(Format.MsoMdoc, "not-cbor".toByteArray()))
        assertTrue(extracted.user.isEmpty())
        assertTrue(extracted.protocol.isEmpty())
    }

    @Test
    fun `empty payload returns empty buckets`() {
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, ByteArray(0)))
        assertTrue(extracted.user.isEmpty())
        assertTrue(extracted.protocol.isEmpty())
    }

    @Test
    fun `flat string claim from disclosure lands in user bucket`() {
        val payload = sdJwt(
            payloadJson = """{"iss":"https://issuer","iat":1700000000,"vct":"https://x","_sd":["h1"],"_sd_alg":"sha-256"}""",
            """["salt1","given_name","Alice"]""",
        )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))
        assertEquals("Alice", extracted.user["given_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested object claim is preserved in user bucket`() {
        val payload = sdJwt(
            payloadJson = """{"iss":"https://issuer","vct":"https://x"}""",
            """["salt","address",{"street":"Main 1","city":"Berlin"}]""",
        )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))
        val address = extracted.user["address"]!!.jsonObject
        assertEquals("Main 1", address["street"]!!.jsonPrimitive.content)
        assertEquals("Berlin", address["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun `array of primitives is preserved in user bucket`() {
        val payload = sdJwt(
            payloadJson = """{"vct":"https://x"}""",
            """["salt","nationalities",["DE","FR"]]""",
        )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))
        val list = extracted.user["nationalities"]!!.jsonArray
        assertEquals(2, list.size)
        assertEquals("DE", list[0].jsonPrimitive.content)
        assertEquals("FR", list[1].jsonPrimitive.content)
    }

    @Test
    fun `protocol claims and timestamps land in protocol bucket`() {
        val payload = sdJwt(
            payloadJson = """{"iss":"https://issuer","iat":1700000000,"exp":1800000000,"nbf":1700000000,"jti":"abc","vct":"https://x","status":{"a":1},"cnf":{"jwk":{"kty":"EC"}},"_sd":["h"],"_sd_alg":"sha-256","family_name":"Doe"}""",
        )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))

        // User bucket: only the visible application claim.
        assertEquals("Doe", extracted.user["family_name"]!!.jsonPrimitive.content)
        listOf("iss", "iat", "exp", "nbf", "jti", "vct", "status", "cnf", "_sd", "_sd_alg")
            .forEach { assertFalse("must not leak $it into user bucket", extracted.user.containsKey(it)) }

        // Protocol bucket: JWT scaffolding plus iat / exp / nbf.
        listOf("iss", "iat", "exp", "nbf", "jti", "vct", "status", "cnf", "_sd", "_sd_alg")
            .forEach { assertTrue("expected $it in protocol bucket", extracted.protocol.containsKey(it)) }
        assertEquals(1700000000L, extracted.protocol["iat"]!!.jsonPrimitive.content.toLong())
        assertEquals(1800000000L, extracted.protocol["exp"]!!.jsonPrimitive.content.toLong())
        assertFalse("application claims must not leak into protocol bucket", extracted.protocol.containsKey("family_name"))
    }

    @Test
    fun `array-element disclosures without a label are skipped`() {
        val payload = sdJwt(
            payloadJson = """{"vct":"https://x"}""",
            // Array-element disclosure: [salt, value] — only 2 entries, must be ignored.
            """["salt","just_a_value"]""",
            """["salt","family_name","Doe"]""",
        )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))
        assertEquals(1, extracted.user.size)
        assertEquals("Doe", extracted.user["family_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed sd-jwt does not crash`() {
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, "not-a-jwt".toByteArray()))
        assertTrue(extracted.user.isEmpty())
        assertTrue(extracted.protocol.isEmpty())
    }
}
