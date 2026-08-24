package dev.digitallabor.elpaso.wallet

import dev.digitallabor.elpaso.wallet.presentation.paso.PasoDetector
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PasoDetectorTest {
    private fun generic(
        rawId: String,
        type: String,
    ): TransactionData = TransactionData.Generic(raw = rawId, type = type, raw_obj = buildJsonObject {})

    @Test
    fun `pasoEntry returns null when no PaSO type is present`() {
        val entries =
            listOf(
                generic("r1", "payment_data"),
                generic("r2", "qcert_creation_acceptance"),
            )
        assertNull(PasoDetector.pasoEntry(entries))
    }

    @Test
    fun `pasoEntry returns the PaSO entry when present`() {
        val paso = generic("r-paso", "urn:paso:sca:global:payment:1")
        val entries = listOf(generic("r1", "payment_data"), paso)
        assertEquals(paso, PasoDetector.pasoEntry(entries))
    }

    @Test
    fun `pasoEntry recognises any urn paso sca prefix`() {
        val custom = generic("r", "urn:paso:sca:com.example:pay:2")
        assertNotNull(PasoDetector.pasoEntry(listOf(custom)))
    }

    /**
     * The EUDI SCA namespace carries the same strong-customer-authentication semantics, so
     * it must inherit the signed-JAR requirement and the simple-profile rule rather than be
     * treated as an ordinary display-only entry.
     */
    @Test
    fun `pasoEntry recognises the urn eudi sca prefix`() {
        val eudi = generic("r-eudi", "urn:eudi:sca:payment:1")
        assertEquals(eudi, PasoDetector.pasoEntry(listOf(generic("r1", "payment_data"), eudi)))
    }

    @Test
    fun `rejectAdvancedProfile throws for one PaSO and one EUDI SCA entry`() {
        val entries =
            listOf(
                generic("r1", "urn:paso:sca:global:payment:1"),
                generic("r2", "urn:eudi:sca:payment:1"),
            )
        assertThrows(PasoDetector.PasoUnsupportedException::class.java) {
            PasoDetector.rejectAdvancedProfile(entries)
        }
    }

    @Test
    fun `rejectAdvancedProfile passes for zero or one PaSO entries`() {
        PasoDetector.rejectAdvancedProfile(emptyList())
        PasoDetector.rejectAdvancedProfile(
            listOf(generic("r", "urn:paso:sca:global:payment:1")),
        )
    }

    @Test
    fun `rejectAdvancedProfile throws when more than one PaSO entry is present`() {
        val entries =
            listOf(
                generic("r1", "urn:paso:sca:global:payment:1"),
                generic("r2", "urn:paso:sca:com.example:pay:2"),
            )
        assertThrows(PasoDetector.PasoUnsupportedException::class.java) {
            PasoDetector.rejectAdvancedProfile(entries)
        }
    }
}
