package dev.digitallabor.elpaso.wallet.presentation.txdata

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ValueTypeFormattersTest {

    @Test
    fun isoCurrencyAmountParsesAndFormats() {
        val formatted = ValueTypeFormatters.formatIsoCurrencyAmount("1234.56 EUR", Locale.GERMANY)
        assertNotNull(formatted)
        // Don't pin exact glyphs (locale-data variance) — just assert the amount + currency made it through.
        assertEquals(true, formatted!!.contains("1.234,56"))
    }

    @Test
    fun booleanRendersLocalised() {
        val en = ValueTypeFormatters.format(JsonPrimitive(true), ValueTypeFormatters.BOOLEAN, Locale.ENGLISH)
        assertEquals(ValueTypeFormatters.Formatted.PlainText("Yes"), en)
        val de = ValueTypeFormatters.format(JsonPrimitive(false), ValueTypeFormatters.BOOLEAN, Locale.GERMAN)
        assertEquals(ValueTypeFormatters.Formatted.PlainText("Nein"), de)
    }

    @Test
    fun frequencyRendersLocalised() {
        val en = ValueTypeFormatters.format(JsonPrimitive("MNTH"), ValueTypeFormatters.FREQUENCY, Locale.ENGLISH)
        assertEquals(ValueTypeFormatters.Formatted.PlainText("Monthly"), en)
        val de = ValueTypeFormatters.format(JsonPrimitive("WEEK"), ValueTypeFormatters.FREQUENCY, Locale.GERMAN)
        assertEquals(ValueTypeFormatters.Formatted.PlainText("Wöchentlich"), de)
    }

    // `unknownFrequencyCodePassesThrough` and `unknownValueTypeRendersAsPlainText` were
    // deleted here. Both asserted the wallet's former permissive stance — render whatever
    // you don't understand as plain text — as though it were a contract. PaSO View §3 makes
    // an unsupported `value_type` or a non-conforming value an *incompatible entry*, and
    // that verdict now belongs to TransactionDataValidator, which is where the replacement
    // assertions live: TransactionDataValidatorValueTypeTest.badFrequencyCodeIsIncompatible
    // and .unsupportedValueTypeIsIncompatible.

    @Test
    fun labelOnlyReturnsLabelOnlyMarker() {
        val out = ValueTypeFormatters.format(JsonPrimitive("ignored"), ValueTypeFormatters.LABEL_ONLY, Locale.ENGLISH)
        assertEquals(ValueTypeFormatters.Formatted.LabelOnly, out)
    }

    @Test
    fun urlReturnsUrl() {
        val out = ValueTypeFormatters.format(JsonPrimitive("https://example.test"), ValueTypeFormatters.URL, Locale.ENGLISH)
        assertEquals(ValueTypeFormatters.Formatted.Url("https://example.test"), out)
    }

    @Test
    fun isoCurrencyMissingSpaceReturnsNull() {
        assertNull(ValueTypeFormatters.formatIsoCurrencyAmount("1234.56EUR", Locale.ENGLISH))
    }

    @Test
    fun isoCurrencyUnknownCurrencyReturnsNull() {
        assertNull(ValueTypeFormatters.formatIsoCurrencyAmount("100 XYZZY", Locale.ENGLISH))
    }

    @Test
    fun nullValueFormatsToEmpty() {
        assertEquals(ValueTypeFormatters.Formatted.PlainText(""), ValueTypeFormatters.format(null, null, Locale.ENGLISH))
        assertEquals(ValueTypeFormatters.Formatted.PlainText(""), ValueTypeFormatters.format(JsonNull, null, Locale.ENGLISH))
    }

    @Test
    fun resolvePathTraversesNestedObjects() {
        val root = JsonObject(
            mapOf(
                "payee" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("Acme Corp"),
                        "id" to JsonPrimitive("acme-123"),
                    ),
                ),
                "amount" to JsonPrimitive("100.00 EUR"),
            ),
        )
        assertEquals(
            JsonPrimitive("Acme Corp"),
            ValueTypeFormatters.resolvePath(root, listOf("payee", "name")),
        )
        assertEquals(
            JsonPrimitive("100.00 EUR"),
            ValueTypeFormatters.resolvePath(root, listOf("amount")),
        )
    }

    @Test
    fun resolvePathMissingSegmentReturnsNull() {
        val root = JsonObject(mapOf("a" to JsonPrimitive("x")))
        assertNull(ValueTypeFormatters.resolvePath(root, listOf("a", "missing")))
        assertNull(ValueTypeFormatters.resolvePath(root, listOf("b")))
    }
}
