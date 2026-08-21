package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DcRegistryEntryMapperTest {
    @Test
    fun `sdJwtClaims flattens a nested tree to dotted display names`() {
        val tree =
            buildJsonObject {
                put("given_name", JsonPrimitive("Ada"))
                putJsonObject("address") {
                    put("locality", JsonPrimitive("Berlin"))
                }
            }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        val byPath = claims.associateBy { it.path }
        assertEquals(setOf(listOf("given_name"), listOf("address", "locality")), byPath.keys)
        assertEquals("Ada", byPath[listOf("given_name")]!!.value)
        assertEquals("Berlin", byPath[listOf("address", "locality")]!!.value)
    }

    @Test
    fun `sdJwtClaims treats an array as a single leaf`() {
        val tree =
            buildJsonObject {
                putJsonArray("nationalities") {
                    add(JsonPrimitive("DE"))
                    add(JsonPrimitive("FR"))
                }
            }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        assertEquals(1, claims.size)
        assertEquals(listOf("nationalities"), claims.single().path)
    }

    @Test
    fun `sdJwtClaims skips the tree root and empty objects`() {
        val tree =
            buildJsonObject {
                putJsonObject("empty") {}
                put("vct", JsonPrimitive("https://example.test/vct"))
            }

        val claims = DcRegistryEntryMapper.sdJwtClaims(tree)

        assertTrue(claims.none { it.path.isEmpty() })
        assertEquals(setOf(listOf("empty"), listOf("vct")), claims.map { it.path }.toSet())
    }

    @Test
    fun `mdocFields keys by namespace and element identifier`() {
        val namespaces: Map<String, Map<String, JsonElement>> =
            mapOf(
                "org.iso.18013.5.1" to
                    mapOf(
                        "family_name" to JsonPrimitive("Lovelace"),
                        "age_over_18" to JsonPrimitive(true),
                    ),
            )

        val fields = DcRegistryEntryMapper.mdocFields(namespaces)

        assertEquals(2, fields.size)
        val familyName = fields.single { it.identifier == "family_name" }
        assertEquals("org.iso.18013.5.1", familyName.namespace)
        assertEquals("Lovelace", familyName.fieldValue)
    }

    @Test
    fun `displayName joins a nested path with dots`() {
        assertEquals(
            "address.locality",
            DcRegistryEntryMapper.displayName(listOf("address", "locality")),
        )
    }

    @Test
    fun `displayValue unwraps primitives and stringifies containers`() {
        assertEquals("Ada", DcRegistryEntryMapper.displayValue(JsonPrimitive("Ada")))
        assertEquals("true", DcRegistryEntryMapper.displayValue(JsonPrimitive(true)))
        val array = buildJsonObject { putJsonArray("x") { add(JsonPrimitive("a")) } }["x"]!!
        assertEquals("""["a"]""", DcRegistryEntryMapper.displayValue(array))
    }
}
