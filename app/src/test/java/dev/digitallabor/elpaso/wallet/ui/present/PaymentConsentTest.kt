package dev.digitallabor.elpaso.wallet.ui.present

import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.presentation.DcqlMatcher
import dev.digitallabor.elpaso.wallet.presentation.PresentationCandidate
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure-Kotlin halves of the payment consent screen: which credential in a
 * candidate is the paying instrument, and which are supporting disclosures shown beneath it.
 *
 * Deliberately no composable tests — the split is extracted precisely so the decision is
 * testable on the JVM without `android.util.*` (see AGENTS.md on unit-test stubs).
 */
class PaymentConsentTest {
    private fun match(
        queryId: String,
        credentialId: String,
    ) = DcqlMatcher.Match(
        queryId = queryId,
        credentialId = credentialId,
        format = Format.SdJwtVc,
        requestedClaimPaths = emptyList(),
        intentToRetain = emptyMap(),
    )

    private fun candidate(vararg matches: DcqlMatcher.Match) = PresentationCandidate(assignments = matches.toList())

    @Test
    fun `splitAssignments picks the paying credential named by credential_ids`() {
        val age = match("age_query", "cred-age")
        val card = match("card_query", "cred-card")

        val (paying, supporting) =
            splitAssignments(candidate(age, card), payingQueryIds = listOf("card_query"))

        assertEquals(card, paying)
        assertEquals(listOf(age), supporting)
    }

    @Test
    fun `splitAssignments falls back to the first assignment without credential_ids`() {
        val card = match("card_query", "cred-card")
        val age = match("age_query", "cred-age")

        val (paying, supporting) =
            splitAssignments(candidate(card, age), payingQueryIds = emptyList())

        assertEquals(card, paying)
        assertEquals(listOf(age), supporting)
    }

    @Test
    fun `splitAssignments falls back when credential_ids names no present query`() {
        val card = match("card_query", "cred-card")
        val age = match("age_query", "cred-age")

        val (paying, supporting) =
            splitAssignments(candidate(card, age), payingQueryIds = listOf("absent_query"))

        assertEquals(card, paying)
        assertEquals(listOf(age), supporting)
    }

    @Test
    fun `splitAssignments leaves no supporting credentials for a single assignment`() {
        val card = match("card_query", "cred-card")

        val (paying, supporting) =
            splitAssignments(candidate(card), payingQueryIds = listOf("card_query"))

        assertEquals(card, paying)
        assertTrue(supporting.isEmpty())
    }

    @Test
    fun `splitAssignments keeps supporting credentials in query order`() {
        val card = match("card_query", "cred-card")
        val age = match("age_query", "cred-age")
        val address = match("address_query", "cred-address")

        val (_, supporting) =
            splitAssignments(candidate(card, age, address), payingQueryIds = listOf("card_query"))

        assertEquals(listOf(age, address), supporting)
    }

    @Test
    fun `splitAssignments yields no paying credential for an empty candidate`() {
        val (paying, supporting) = splitAssignments(candidate(), payingQueryIds = emptyList())

        assertNull(paying)
        assertTrue(supporting.isEmpty())
    }

    @Test
    fun `paymentSummaryOf carries the EUDI SCA entry's credential_ids`() {
        val entry =
            TransactionData.EudiScaPayment(
                raw = "irrelevant",
                transactionId = "tx-1",
                payeeName = "Rock Legends",
                payeeId = "merchant-1",
                amountDisplay = "$ 592.68",
                credentialIds = listOf("card_query"),
            )

        val summary = paymentSummaryOf(listOf(entry))

        assertNotNull(summary)
        assertEquals("$ 592.68", summary!!.amount)
        assertEquals("Rock Legends", summary.payee)
        assertEquals(listOf("card_query"), summary.payingQueryIds)
    }

    @Test
    fun `paymentSummaryOf leaves payingQueryIds empty for entry types without targeting`() {
        val entry =
            TransactionData.PaymentData(
                raw = "irrelevant",
                payeeName = "Rock Legends",
                payeeAccount = null,
                amount = "12.00",
                currency = "EUR",
                reference = null,
            )

        val summary = paymentSummaryOf(listOf(entry))

        assertNotNull(summary)
        assertTrue(summary!!.payingQueryIds.isEmpty())
    }
}
