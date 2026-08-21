package dev.digitallabor.elpaso.wallet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LocalizedLabelTest {

    @Test
    fun emptyListReturnsNull() {
        assertNull(emptyList<LocalizedLabel>().pick(Locale.ENGLISH))
    }

    @Test
    fun exactTagWins() {
        val labels = listOf(
            LocalizedLabel(locale = "en", value = "Authorize", valueType = null),
            LocalizedLabel(locale = "en-US", value = "Authorize (US)", valueType = null),
            LocalizedLabel(locale = "de", value = "Bestätigen", valueType = null),
        )
        assertEquals("Authorize (US)", labels.pick(Locale("en", "US"))?.value)
    }

    @Test
    fun languageOnlyMatchWins() {
        val labels = listOf(
            LocalizedLabel(locale = "de-AT", value = "Bestätigen (AT)", valueType = null),
            LocalizedLabel(locale = "de", value = "Bestätigen", valueType = null),
        )
        assertEquals("Bestätigen", labels.pick(Locale.GERMAN)?.value)
    }

    @Test
    fun localelessEntryIsTreatedAsDefault() {
        val labels = listOf(
            LocalizedLabel(locale = null, value = "Default", valueType = null),
            LocalizedLabel(locale = "de", value = "Bestätigen", valueType = null),
        )
        assertEquals("Default", labels.pick(Locale.FRENCH)?.value)
    }

    @Test
    fun firstEntryIsFinalFallback() {
        val labels = listOf(
            LocalizedLabel(locale = "ja", value = "承認", valueType = null),
            LocalizedLabel(locale = "ko", value = "승인", valueType = null),
        )
        assertEquals("承認", labels.pick(Locale.ENGLISH)?.value)
    }
}
