package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the PaSO Proof Metadata §3.3 primitives: extended grapheme-cluster counting
 * ([UAX29]) and the character prohibitions that apply to every label and to
 * `transaction_data` payload string values.
 */
class GraphemeTextTest {
    private val g = GraphemeCounter.Default

    @Test
    fun countsAsciiOneToOne() = assertEquals(5, g.count("hello"))

    @Test
    fun countsCombiningMarkAsOneCluster() {
        // "e" + U+0301 COMBINING ACUTE ACCENT is a single extended grapheme cluster,
        // though it is two chars. Counting chars here would under-report the cap.
        assertEquals(1, g.count("e\u0301"))
    }

    @Test
    fun countsSurrogatePairAsOneCluster() {
        // U+1F600 GRINNING FACE is one cluster but two UTF-16 chars.
        assertEquals(1, g.count("\uD83D\uDE00"))
    }

    @Test
    fun countsEmptyStringAsZero() = assertEquals(0, g.count(""))

    @Test
    fun controlCharDetected() {
        assertTrue(LabelText.hasControlChar("a\u0007b")) // C0 BEL
        assertTrue(LabelText.hasControlChar("a\u0000b")) // C0 NUL, lower bound
        assertTrue(LabelText.hasControlChar("a\u001Fb")) // C0 upper bound
        assertTrue(LabelText.hasControlChar("a\u007Fb")) // C1 DEL, lower bound
        assertTrue(LabelText.hasControlChar("a\u0085b")) // C1 NEL
        assertTrue(LabelText.hasControlChar("a\u009Fb")) // C1 upper bound
        assertFalse(LabelText.hasControlChar("normal text"))
    }

    @Test
    fun newlineIsAControlChar() {
        // §3.3 prohibits control characters, which forbids line breaks inside labels —
        // wrapping is the wallet's decision, not the issuer's.
        assertTrue(LabelText.hasControlChar("two\nlines"))
        assertTrue(LabelText.hasControlChar("two\rlines"))
        assertTrue(LabelText.hasControlChar("two\tcols"))
    }

    @Test
    fun directionalOverrideDetected() {
        assertTrue(LabelText.hasDirectionalOverride("a\u202Ab")) // LRE, lower bound
        assertTrue(LabelText.hasDirectionalOverride("a\u202Eb")) // RLO, upper bound
        assertFalse(LabelText.hasDirectionalOverride("plain"))
        // Isolates are explicitly permitted and must not be confused with overrides.
        assertFalse(LabelText.hasDirectionalOverride("a\u2066b\u2069"))
    }

    @Test
    fun isolatesMustBalance() {
        assertTrue(LabelText.hasBalancedIsolates("x\u2066y\u2069z")) // LRI..PDI
        assertTrue(LabelText.hasBalancedIsolates("\u2067a\u2069\u2068b\u2069")) // RLI, FSI
        assertTrue(LabelText.hasBalancedIsolates("\u2066\u2066a\u2069\u2069")) // nested
        assertTrue(LabelText.hasBalancedIsolates("no isolates at all"))
        assertFalse(LabelText.hasBalancedIsolates("x\u2066y")) // unterminated
        assertFalse(LabelText.hasBalancedIsolates("x\u2069y")) // underflow: PDI first
        assertFalse(LabelText.hasBalancedIsolates("\u2066a\u2069\u2069")) // trailing underflow
    }
}
