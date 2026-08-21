package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DcIssuanceRegistryBlobTest {
    private val icon = ByteArray(64) { it.toByte() }

    private fun jsonOf(blob: ByteArray): kotlinx.serialization.json.JsonObject {
        val offset = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val text = String(blob, offset, blob.size - offset, Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `writes a little endian offset just past the icon`() {
        val blob = DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null)
        val offset = ByteBuffer.wrap(blob, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(4 + icon.size, offset)
    }

    @Test
    fun `writes the icon bytes verbatim after the header`() {
        val blob = DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null)
        assertArrayEquals(icon, blob.copyOfRange(4, 4 + icon.size))
    }

    @Test
    fun `describes the icon location in the json`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        val iconJson = json["display"]!!.jsonObject["icon"]!!.jsonObject
        assertEquals(4, iconJson["start"]!!.jsonPrimitive.int)
        assertEquals(icon.size, iconJson["length"]!!.jsonPrimitive.int)
    }

    @Test
    fun `carries title and subtitle`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        val display = json["display"]!!.jsonObject
        assertEquals("El Paso", display["title"]!!.jsonPrimitive.content)
        assertEquals("Save it", display["subtitle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `omits capabilities entirely when the allowlist is null`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", "Save it", null))
        assertTrue("capabilities" !in json.keys)
    }

    @Test
    fun `emits one capabilities key per allowed issuer`() {
        val json =
            jsonOf(
                DcIssuanceRegistryBlob.build(
                    icon,
                    "El Paso",
                    "Save it",
                    listOf("https://a.example", "https://b.example"),
                ),
            )
        val capabilities = json["capabilities"]!!.jsonObject
        assertEquals(setOf("https://a.example", "https://b.example"), capabilities.keys)
    }

    @Test
    fun `omits subtitle when null`() {
        val json = jsonOf(DcIssuanceRegistryBlob.build(icon, "El Paso", null, null))
        assertTrue("subtitle" !in json["display"]!!.jsonObject.keys)
    }
}
