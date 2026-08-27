package dev.digitallabor.elpaso.wallet.dcapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet's matcher reports matches through `AddEntrySet` / `AddEntryToSet`
 * (`matcher/upstream/openid4vp1_0.c:135`), so Credential Manager returns the user's
 * choice via `ProviderGetCredentialRequest.selectedCredentialSet` — never through the
 * single-entry `selectedEntryId`. These tests pin the parser that turns that set, plus
 * the per-credential metadata the matcher attaches, into the wallet's own model.
 *
 * The metadata shape is built at `openid4vp1_0.c:57-67`. Only `dcql_cred_id` is load
 * bearing for us: it names the DCQL credential query this credential was matched
 * against, which is what lets the wallet reproduce the exact assignment the user saw
 * instead of re-deriving candidates and asking again.
 */
class DcApiSelectionTest {
    private fun metadata(
        dcqlCredId: String?,
        setIndex: String? = "0",
    ): String {
        val credId = dcqlCredId?.let { """"dcql_cred_id":"$it",""" }.orEmpty()
        val set =
            setIndex?.let { """"dcql_credential_set_index":"$it","dcql_option_index":"0",""" }.orEmpty()
        return """
            {"claims":{"given_name":{"verification":{"display":"First name"}}},
             "dc_request_index":0,
             $credId
             $set
             "unused":true}
            """.trimIndent()
    }

    @Test
    fun `fromEntrySet keeps every selected credential in the set`() {
        // The regression guard for the credential_sets starvation bug: a two-credential
        // request must yield two eligible ids, not one.
        val selection =
            DcApiSelection.fromEntrySet(
                setId = "set-0",
                credentials =
                    listOf(
                        "cred-sparkasse" to metadata("sparkassencard"),
                        "cred-av" to metadata("av_sdjwt"),
                    ),
            )

        assertEquals("set-0", selection?.setId)
        assertEquals(setOf("cred-sparkasse", "cred-av"), selection?.credentialIds)
    }

    @Test
    fun `fromEntrySet pins each credential to its dcql query id`() {
        val selection =
            DcApiSelection.fromEntrySet(
                setId = "set-0",
                credentials =
                    listOf(
                        "cred-sparkasse" to metadata("sparkassencard"),
                        "cred-av" to metadata("av_sdjwt"),
                    ),
            )

        assertEquals(
            setOf("cred-sparkasse" to "sparkassencard", "cred-av" to "av_sdjwt"),
            selection?.assignmentPins,
        )
    }

    @Test
    fun `fromEntrySet tolerates metadata without a dcql_cred_id`() {
        // A matcher build that omits the key must still narrow eligibility by id; it just
        // cannot pin the assignment, so the candidate resolver decides.
        val selection =
            DcApiSelection.fromEntrySet(
                setId = "set-0",
                credentials = listOf("cred-a" to metadata(dcqlCredId = null)),
            )

        assertEquals(setOf("cred-a"), selection?.credentialIds)
        assertTrue(selection!!.assignmentPins.isEmpty())
    }

    @Test
    fun `fromEntrySet tolerates absent and malformed metadata`() {
        val selection =
            DcApiSelection.fromEntrySet(
                setId = "set-0",
                credentials = listOf("cred-a" to null, "cred-b" to "{not json"),
            )

        assertEquals(setOf("cred-a", "cred-b"), selection?.credentialIds)
        assertTrue(selection!!.assignmentPins.isEmpty())
    }

    @Test
    fun `fromEntrySet returns null when the set is empty`() {
        assertNull(DcApiSelection.fromEntrySet(setId = "set-0", credentials = emptyList()))
    }

    @Test
    fun `fromEntrySet ignores entries with a blank credential id`() {
        val selection =
            DcApiSelection.fromEntrySet(
                setId = "set-0",
                credentials = listOf("" to metadata("q1"), "cred-b" to metadata("q2")),
            )

        assertEquals(setOf("cred-b"), selection?.credentialIds)
    }

    @Test
    fun `ofSingleEntry wraps the legacy single-entry selection`() {
        val selection = DcApiSelection.ofSingleEntry("cred-a")

        assertNull(selection?.setId)
        assertEquals(setOf("cred-a"), selection?.credentialIds)
        // No matcher metadata on the legacy path, so nothing to pin.
        assertTrue(selection!!.assignmentPins.isEmpty())
    }

    @Test
    fun `ofSingleEntry is null for a null or blank id`() {
        assertNull(DcApiSelection.ofSingleEntry(null))
        assertNull(DcApiSelection.ofSingleEntry(""))
        assertNull(DcApiSelection.ofSingleEntry("   "))
    }
}
