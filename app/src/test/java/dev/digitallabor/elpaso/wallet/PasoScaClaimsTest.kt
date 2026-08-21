package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.presentation.paso.PasoScaClaims
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasoScaClaimsTest {

    private val sample = PasoScaClaims(
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

    private fun decode(fragment: String): JsonObject =
        Json.parseToJsonElement("{$fragment}") as JsonObject

    @Test
    fun `fragment is parseable JSON when wrapped in braces`() {
        val obj = decode(sample.toJsonFragment())
        assertEquals("deeec2b0-3bea-4477-bd5d-e3462a709481", obj["jti"]?.jsonPrimitive?.content)
        assertEquals("direct_post.jwt", obj["response_mode"]?.jsonPrimitive?.content)
        assertEquals("de", obj["display_locale"]?.jsonPrimitive?.content)
        assertEquals("sha-256", obj["transaction_data_hash_alg"]?.jsonPrimitive?.content)
        assertEquals(
            "android:dev.digitallabor.elpaso.wallet:1.2.3",
            obj["wallet_instance_version"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `amr is rendered as a JSON array of strings`() {
        val obj = decode(sample.toJsonFragment())
        val amr = obj["amr"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("hwk", "bio_strong"), amr)
    }

    @Test
    fun `metadata_integrity is omitted when null`() {
        val fragment = sample.toJsonFragment()
        assertFalse(
            "metadata_integrity must be absent when not set",
            fragment.contains("metadata_integrity"),
        )
        val obj = decode(fragment)
        assertNull(obj["metadata_integrity"])
    }

    @Test
    fun `metadata_integrity is emitted when present`() {
        val withMetadata = sample.copy(
            metadataIntegrity = "sha256-K3L5x7nMqYdP2fR8vQwJ1bHgT9sUcA4eZpXo6yD0mEk=",
        )
        val obj = decode(withMetadata.toJsonFragment())
        assertEquals(
            "sha256-K3L5x7nMqYdP2fR8vQwJ1bHgT9sUcA4eZpXo6yD0mEk=",
            obj["metadata_integrity"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `string values with quotes and backslashes are escaped`() {
        // wallet_instance_version is the only string that could plausibly contain quotes
        // via BuildConfig, but the escaping rule is general — exercise it on jti.
        val tricky = sample.copy(jti = """quote"and\backslash""")
        val obj = decode(tricky.toJsonFragment())
        assertEquals("""quote"and\backslash""", obj["jti"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fragment splices cleanly into an existing JSON object`() {
        val existing = """"aud":"x509_san_dns:shop.example.com","nonce":"abc","iat":1741269093"""
        val spliced = "{$existing,${sample.toJsonFragment()}}"
        val obj = Json.parseToJsonElement(spliced) as JsonObject
        assertTrue(obj.containsKey("aud"))
        assertTrue(obj.containsKey("nonce"))
        assertTrue(obj.containsKey("jti"))
        assertTrue(obj.containsKey("transaction_data_hash"))
    }
}
