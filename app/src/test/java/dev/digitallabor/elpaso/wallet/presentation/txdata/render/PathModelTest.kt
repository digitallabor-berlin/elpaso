package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ClaimMetadata.path` is `List<String?>`, where a `null` segment is the PaSO View §2
 * array wildcard. These helpers are the vocabulary the validator, the template
 * interpolator and the wildcard expander all share.
 */
class PathModelTest {
    @Test
    fun wildcardCountAndKey() {
        val p: List<String?> = listOf("items", null, "amount")
        assertEquals(1, p.wildcardCount())
        assertTrue(p.hasWildcard())
        assertEquals("items.[].amount", p.renderKey())
    }

    @Test
    fun noWildcard() {
        val p: List<String?> = listOf("payee", "name")
        assertEquals(0, p.wildcardCount())
        assertFalse(p.hasWildcard())
        assertEquals("payee.name", p.renderKey())
    }

    @Test
    fun countsMultipleWildcards() {
        val p: List<String?> = listOf("a", null, "b", null)
        assertEquals(2, p.wildcardCount())
        assertEquals("a.[].b.[]", p.renderKey())
    }

    @Test
    fun emptyPath() {
        val p: List<String?> = emptyList()
        assertEquals(0, p.wildcardCount())
        assertFalse(p.hasWildcard())
        assertEquals("", p.renderKey())
    }
}
