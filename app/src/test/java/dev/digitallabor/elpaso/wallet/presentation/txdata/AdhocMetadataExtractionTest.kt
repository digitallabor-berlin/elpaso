package dev.digitallabor.elpaso.wallet.presentation.txdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure half of [TransactionData.adhocMetadataJwt] — the `metadata`
 * parameter added to `transaction_data` entries by paso-proof-metadata.md §5.1.
 *
 * The public property decodes base64url via `android.util.Base64`, which returns
 * null under the JVM unit-test stubs (see AGENTS.md §Build quirks). The extraction
 * itself is therefore pure over an already-decoded [JsonObject] and tested here;
 * only the trivial decode step is untested on the JVM.
 */
class AdhocMetadataExtractionTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun returnsTheMetadataJwtWhenPresent() {
        val entry =
            obj(
                """
                {
                  "type": "urn:paso:sca:dev.digitallabor:limitchange:1",
                  "credential_ids": ["sparkassen_auth"],
                  "payload": { "old_limit": "1000.00 EUR" },
                  "metadata": "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJ4In0.sig"
                }
                """.trimIndent(),
            )
        assertEquals(
            "eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJ4In0.sig",
            TransactionData.extractAdhocMetadataJwt(entry),
        )
    }

    @Test
    fun returnsNullWhenTheParameterIsAbsent() {
        // §5.1 makes `metadata` OPTIONAL — absence is the ordinary case and must not
        // be confused with a present-but-broken value, which is a hard failure.
        val entry = obj("""{ "type": "urn:paso:sca:global:payment:1", "payload": {} }""")
        assertNull(TransactionData.extractAdhocMetadataJwt(entry))
    }

    @Test
    fun returnsNullForABlankValue() {
        val entry = obj("""{ "type": "x", "metadata": "   " }""")
        assertNull(TransactionData.extractAdhocMetadataJwt(entry))
    }

    @Test
    fun returnsNullWhenTheValueIsNotAJsonString() {
        // §5.1 types `metadata` as a string. A JSON object here would otherwise reach
        // `jsonPrimitive` and throw, taking down parsing of an entry that is merely
        // malformed in an optional field.
        assertNull(TransactionData.extractAdhocMetadataJwt(obj("""{ "type": "x", "metadata": { "a": 1 } }""")))
        assertNull(TransactionData.extractAdhocMetadataJwt(obj("""{ "type": "x", "metadata": 42 }""")))
        assertNull(TransactionData.extractAdhocMetadataJwt(obj("""{ "type": "x", "metadata": null }""")))
    }
}
