package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.presentation.builder.SdJwtPresentationBuilder
import dev.digitallabor.elpaso.wallet.presentation.paso.PasoScaClaims
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SdJwtPresentationBuilderPasoTest {

    private val baseClaims = PasoScaClaims(
        jti = "deeec2b0-3bea-4477-bd5d-e3462a709481",
        responseMode = "direct_post.jwt",
        displayLocale = "de",
        amr = listOf("hwk", "bio_strong"),
        transactionDataHash = "OJcnQQByvV1iTYxiQQQx4dact-TNnSG-Ku_cs_6g55Q",
        transactionDataHashAlg = "sha-256",
        metadataIntegrity = null,
        requestIntegrity = "sha256-7Hn3B4x9f2kLmNpQrStUvWxYz0123456789abcdefg=",
        walletInstanceVersion = "android:dev.digitallabor.elpaso.wallet:1.2.3",
    )

    @Test
    fun `payload without paso claims preserves today's shape`() {
        val payload = SdJwtPresentationBuilder.assemblePayloadJson(
            audience = "x509_san_dns:shop.example.com",
            nonce = "bUtJdjJESWdmTWNjb011YQ",
            iatSeconds = 1741269093L,
            sdHash = "Re-CtLZfjGLErKy3eSriZ4bBx3AtUH5Q5wsWiiWKIwY",
            transactionDataHashes = listOf("OJcnQQByvV1iTYxiQQQx4dact-TNnSG-Ku_cs_6g55Q"),
            pasoClaims = null,
        )
        val obj = Json.parseToJsonElement(payload) as JsonObject
        assertEquals("x509_san_dns:shop.example.com", obj["aud"]?.jsonPrimitive?.content)
        assertEquals("bUtJdjJESWdmTWNjb011YQ", obj["nonce"]?.jsonPrimitive?.content)
        assertEquals(1741269093L, obj["iat"]?.jsonPrimitive?.content?.toLong())
        assertEquals("Re-CtLZfjGLErKy3eSriZ4bBx3AtUH5Q5wsWiiWKIwY", obj["sd_hash"]?.jsonPrimitive?.content)
        assertEquals("sha-256", obj["transaction_data_hashes_alg"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("OJcnQQByvV1iTYxiQQQx4dact-TNnSG-Ku_cs_6g55Q"),
            obj["transaction_data_hashes"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertFalse("no SCA claims when pasoClaims is null", payload.contains("\"jti\""))
        assertFalse(payload.contains("\"transaction_data_hash\":"))
    }

    @Test
    fun `payload with paso claims includes all SCA Response Claims at top level`() {
        val payload = SdJwtPresentationBuilder.assemblePayloadJson(
            audience = "x509_san_dns:shop.example.com",
            nonce = "bUtJdjJESWdmTWNjb011YQ",
            iatSeconds = 1741269093L,
            sdHash = "Re-CtLZfjGLErKy3eSriZ4bBx3AtUH5Q5wsWiiWKIwY",
            transactionDataHashes = listOf("OJcnQQByvV1iTYxiQQQx4dact-TNnSG-Ku_cs_6g55Q"),
            pasoClaims = baseClaims,
        )
        val obj = Json.parseToJsonElement(payload) as JsonObject

        // Standard KB-JWT claims still present.
        assertNotNull(obj["aud"])
        assertNotNull(obj["nonce"])
        assertNotNull(obj["iat"])
        assertNotNull(obj["sd_hash"])
        assertNotNull(obj["transaction_data_hashes"])

        // SCA Response Claims (§6.1) at top level.
        assertEquals(baseClaims.jti, obj["jti"]?.jsonPrimitive?.content)
        assertEquals(baseClaims.responseMode, obj["response_mode"]?.jsonPrimitive?.content)
        assertEquals(baseClaims.displayLocale, obj["display_locale"]?.jsonPrimitive?.content)
        assertEquals(
            baseClaims.amr,
            obj["amr"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(
            baseClaims.transactionDataHash,
            obj["transaction_data_hash"]?.jsonPrimitive?.content,
        )
        assertEquals("sha-256", obj["transaction_data_hash_alg"]?.jsonPrimitive?.content)
        assertEquals(
            baseClaims.requestIntegrity,
            obj["request_integrity"]?.jsonPrimitive?.content,
        )
        assertEquals(
            baseClaims.walletInstanceVersion,
            obj["wallet_instance_version"]?.jsonPrimitive?.content,
        )
        assertNull("metadata_integrity must be absent when not used", obj["metadata_integrity"])
    }

    @Test
    fun `payload with paso claims but no transaction_data_hashes still emits both`() {
        // Defensive shape check — if the verifier only sent a PaSO entry that we couldn't
        // hash for the plural array path, the singular transaction_data_hash from the SCA
        // claims must still land.
        val payload = SdJwtPresentationBuilder.assemblePayloadJson(
            audience = "aud",
            nonce = "n",
            iatSeconds = 0L,
            sdHash = "h",
            transactionDataHashes = emptyList(),
            pasoClaims = baseClaims,
        )
        val obj = Json.parseToJsonElement(payload) as JsonObject
        assertNull(obj["transaction_data_hashes"])
        assertEquals(
            baseClaims.transactionDataHash,
            obj["transaction_data_hash"]?.jsonPrimitive?.content,
        )
    }
}
