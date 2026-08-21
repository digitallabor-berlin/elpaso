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

    private fun cred(
        format: Format,
        payload: ByteArray,
    ): Credential =
        Credential(
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

    private fun b64u(json: String): String = urlEncoder.encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun b64u(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)

    private fun sdJwt(
        payloadJson: String,
        vararg disclosureJsons: String,
    ): ByteArray {
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
        val ageDataItem =
            org.multipaz.cbor.CborArray
                .builder()
                .add(30L)
                .end()
                .build()
                .asArray[0]

        val issuerSignedItemsCbor =
            buildMdocPayload(
                namespaces =
                    mapOf(
                        "org.iso.18013.5.1" to
                            mapOf(
                                "given_name" to
                                    org.multipaz.cbor.Cbor
                                        .encode(aliceDataItem),
                                "age" to
                                    org.multipaz.cbor.Cbor
                                        .encode(ageDataItem),
                            ),
                    ),
            )
        val payload = b64u(issuerSignedItemsCbor).toByteArray(Charsets.UTF_8)
        val extracted = CredentialClaims.extract(cred(Format.MsoMdoc, payload))

        assertEquals("Alice", extracted.user["given_name"]!!.jsonPrimitive.content)
        assertEquals("30", extracted.user["age"]!!.jsonPrimitive.content)
        assertEquals("cfg", extracted.protocol["docType"]!!.jsonPrimitive.content)
    }

    private fun buildMdocPayload(namespaces: Map<String, Map<String, ByteArray>>): ByteArray {
        val nameSpacesBuilder =
            org.multipaz.cbor.CborMap
                .builder()
        for ((ns, elements) in namespaces) {
            val nsArrayBuilder =
                org.multipaz.cbor.CborArray
                    .builder()
            for ((identifier, valueBytes) in elements) {
                val item =
                    org.multipaz.mdoc.issuersigned.IssuerSignedItem(
                        digestId = 0L,
                        random = kotlinx.io.bytestring.ByteString(byteArrayOf(1, 2, 3)),
                        dataElementIdentifier = identifier,
                        dataElementValue =
                            org.multipaz.cbor.Cbor
                                .decode(valueBytes),
                    )
                nsArrayBuilder.add(
                    org.multipaz.cbor.Tagged(
                        24L,
                        org.multipaz.cbor.Bstr(
                            org.multipaz.cbor.Cbor
                                .encode(item.toDataItem()),
                        ),
                    ),
                )
            }
            nameSpacesBuilder.put(ns, nsArrayBuilder.end().build())
        }

        val issuerSignedMap =
            org.multipaz.cbor.CborMap
                .builder()
                .put("nameSpaces", nameSpacesBuilder.end().build())
                .put(
                    "issuerAuth",
                    org.multipaz.cbor.CborArray
                        .builder()
                        .end()
                        .build(),
                ) // Dummy
                .end()
                .build()

        return org.multipaz.cbor.Cbor
            .encode(issuerSignedMap)
    }

    /**
     * Real mso_mdoc payload issued by foundry.digitallabor.dev on 2026-08-21. Both of its
     * `IssuerSignedItem`s encode `random` as a 16-element CBOR array of unsigned ints
     * instead of the `bstr` required by ISO/IEC 18013-5 §8.3.2.1.2.2. multipaz's strict
     * decoder rejects that outright, which blanked the whole detail screen. Kept verbatim
     * as a regression fixture; it carries no personal data beyond two age assertions.
     */
    private val avPayloadWithNonConformantRandom =
        "ompuYW1lU3BhY2VzoXFldS5ldXJvcGEuZWMuYXYuMYLYGFhcpGhkaWdlc3RJRAFmcmFuZG9t" +
            "kBgoGCkYLRhAGP8YPQgYdBIYagIYcRj2GG0YrhgqcWVsZW1lbnRJZGVudGlmaWVya2FnZV9v" +
            "dmVyXzE2bGVsZW1lbnRWYWx1ZfXYGFhepGhkaWdlc3RJRAJmcmFuZG9tkBgYGDkYdBhxGGgY" +
            "mRhYGDMY0hi5GB0YJBiTGMoEGKlxZWxlbWVudElkZW50aWZpZXJrYWdlX292ZXJfMThsZWxl" +
            "bWVudFZhbHVl9Wppc3N1ZXJBdXRohEOhASahGCFZAbYwggGyMIIBWaADAgECAhQodAcrjhE9" +
            "inDqNXwi5b2kg6GHVDAKBggqhkjOPQQDAjA1MTMwMQYDVQQDDCpGb3VuZHJ5IFJvb3QgQ0Eg" +
            "KGZvdW5kcnkuZGlnaXRhbGxhYm9yLmRldikwHhcNMjYwNzI4MTE0OTUxWhcNMjcwNzI4MTE0" +
            "OTUxWjAkMSIwIAYDVQQDDBlGb3VuZHJ5IHN0YXR1c2xpc3Rfc2lnbmVyMFkwEwYHKoZIzj0C" +
            "AQYIKoZIzj0DAQcDQgAE9w9p_l5FOFodEplOeHhzWJbxxHfad97M-V7q5OKr7I51c8W6YAzL" +
            "9UUpP1fBTqo6hJSzfen0lKniQCrE5EPwnaNYMFYwHwYDVR0jBBgwFoAUObdXiN1rPGntlfpM" +
            "l2L0f-RfXoMwIwYDVR0RBBwwGoIYZm91bmRyeS5kaWdpdGFsbGFib3IuZGV2MA4GA1UdDwEB" +
            "_wQEAwIHgDAKBggqhkjOPQQDAgNHADBEAiBWoHP1itJK7JQcQIFCvu9YjY-lY9ja0nVRTDvV" +
            "kwqz5wIgVmhckKmpPD-17AuSrUVdqBrXKcq-b0i-8a4pKLKZC8lZAbfYGFkBsqZndmVyc2lv" +
            "bmMxLjBvZGlnZXN0QWxnb3JpdGhtZ1NIQS0yNTZnZG9jVHlwZXFldS5ldXJvcGEuZWMuYXYu" +
            "MWx2YWx1ZURpZ2VzdHOhcWV1LmV1cm9wYS5lYy5hdi4xogGYIBh4GKsYiBinGJEYkxgiGFAY" +
            "5hhAGDoYOxg4GOcYswUYiRinGO8YNxgxGFoYuxgZGEUJGCsYfhh6GOAWGPMCmCAYPRg6GG4Y" +
            "zBj_GNEYsxjIGLgYSRh0GKUYcRiiGGoYthgeGMQYQBiCEhjFGEEYehiDGJYYpxhAGC8YehiF" +
            "GIhtZGV2aWNlS2V5SW5mb6FpZGV2aWNlS2V5pAECIAEhWCCf3aGvljzX0RU4rdovS3pdWoQv" +
            "HtMYSvZS2MXRs-yEvCJYICsN16DGyKxL5ajIfRu5EbPvIP2SujOEUIExcuwmsjMZbHZhbGlk" +
            "aXR5SW5mb6Nmc2lnbmVkwHQyMDI2LTA4LTIxVDEyOjQ1OjQyWml2YWxpZEZyb23AdDIwMjYt" +
            "MDgtMjFUMTI6NDU6NDJaanZhbGlkVW50aWzAdDIwMjYtMTEtMTlUMTI6NDU6NDJaWEAzZr3u" +
            "MGAN-vOapNRz4jgZcjoJpwyA_wMxuH6udgDwkSLg2nFWl2hrh_EZJxMuG7gYhEQFnNijoNsm" +
            "rHEdTsbn"

    /**
     * Builds a base64url `IssuerSigned` payload whose single `IssuerSignedItem` encodes
     * `random` as a CBOR array of unsigned ints rather than a `bstr` — the shape a
     * non-conformant issuer emits when it serialises a byte array as a numeric array.
     */
    private fun mdocPayloadWithArrayEncodedRandom(
        namespace: String,
        elementIdentifier: String,
    ): ByteArray {
        val arrayRandom =
            org.multipaz.cbor.CborArray
                .builder()
                .add(1L)
                .add(2L)
                .add(3L)
                .add(4L)
                .end()
                .build()
        val item =
            org.multipaz.cbor.CborMap
                .builder()
                .put("digestID", 1L)
                .put("random", arrayRandom)
                .put("elementIdentifier", elementIdentifier)
                .put("elementValue", true)
                .end()
                .build()
        val itemsArray =
            org.multipaz.cbor.CborArray
                .builder()
                .add(
                    org.multipaz.cbor.Tagged(
                        24L,
                        org.multipaz.cbor.Bstr(
                            org.multipaz.cbor.Cbor
                                .encode(item),
                        ),
                    ),
                ).end()
                .build()
        val nameSpaces =
            org.multipaz.cbor.CborMap
                .builder()
                .put(namespace, itemsArray)
                .end()
                .build()
        val issuerSigned =
            org.multipaz.cbor.CborMap
                .builder()
                .put("nameSpaces", nameSpaces)
                .put(
                    "issuerAuth",
                    org.multipaz.cbor.CborArray
                        .builder()
                        .end()
                        .build(),
                ).end()
                .build()
        return b64u(
            org.multipaz.cbor.Cbor
                .encode(issuerSigned),
        ).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `mdoc item whose random is an array instead of a bstr still yields its claim`() {
        val payload =
            mdocPayloadWithArrayEncodedRandom(
                namespace = "eu.europa.ec.av.1",
                elementIdentifier = "age_over_18",
            )

        val extracted = CredentialClaims.extract(cred(Format.MsoMdoc, payload))

        assertEquals("true", extracted.user["age_over_18"]!!.jsonPrimitive.content)
    }

    @Test
    fun `real AV payload with non-spec random exposes both age claims`() {
        val payload = avPayloadWithNonConformantRandom.toByteArray(Charsets.UTF_8)

        val extracted = CredentialClaims.extract(cred(Format.MsoMdoc, payload))

        assertEquals("true", extracted.user["age_over_16"]!!.jsonPrimitive.content)
        assertEquals("true", extracted.user["age_over_18"]!!.jsonPrimitive.content)
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
        val payload =
            sdJwt(
                payloadJson = """{"iss":"https://issuer","iat":1700000000,"vct":"https://x","_sd":["h1"],"_sd_alg":"sha-256"}""",
                """["salt1","given_name","Alice"]""",
            )
        val extracted = CredentialClaims.extract(cred(Format.SdJwtVc, payload))
        assertEquals("Alice", extracted.user["given_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested object claim is preserved in user bucket`() {
        val payload =
            sdJwt(
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
        val payload =
            sdJwt(
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
        val payload =
            sdJwt(
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
        assertEquals(
            1700000000L,
            extracted.protocol["iat"]!!
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            1800000000L,
            extracted.protocol["exp"]!!
                .jsonPrimitive.content
                .toLong(),
        )
        assertFalse("application claims must not leak into protocol bucket", extracted.protocol.containsKey("family_name"))
    }

    @Test
    fun `array-element disclosures without a label are skipped`() {
        val payload =
            sdJwt(
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
