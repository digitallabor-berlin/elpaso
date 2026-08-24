package dev.digitallabor.elpaso.wallet.presentation

import dev.digitallabor.elpaso.wallet.domain.model.Format
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQuery
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQueryIds
import eu.europa.ec.eudi.openid4vp.dcql.CredentialSetQuery
import eu.europa.ec.eudi.openid4vp.dcql.CredentialSets
import eu.europa.ec.eudi.openid4vp.dcql.Credentials
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.europa.ec.eudi.openid4vp.Format as LibFormat

class DcqlCandidateResolverTest {
    private val resolver = DcqlCandidateResolver()

    // --- fixtures -------------------------------------------------------

    /**
     * The resolver only reads `id` off each CredentialQuery — format filtering and
     * claim extraction already happened in DcqlMatcher — so an empty `meta` is fine.
     */
    private fun credQuery(id: String) =
        CredentialQuery(
            id = QueryId(id),
            format = LibFormat.SdJwtVc,
            meta = JsonObject(emptyMap()),
        )

    private fun dcql(
        ids: List<String>,
        sets: List<CredentialSetQuery>? = null,
    ) = DCQL(
        credentials = Credentials(ids.map(::credQuery)),
        credentialSets = sets?.let { CredentialSets(it) },
    )

    private fun set(
        vararg options: List<String>,
        required: Boolean? = null,
    ) = CredentialSetQuery(
        options = options.map { option -> CredentialQueryIds(option.map(::QueryId)) },
        required = required,
    )

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

    private fun List<PresentationCandidate>.credentialIds(): List<List<String>> =
        map { candidate -> candidate.assignments.map { it.credentialId } }

    // --- tests ----------------------------------------------------------

    @Test
    fun `null query yields no candidates`() {
        assertEquals(emptyList<PresentationCandidate>(), resolver.resolve(null, emptyList()))
    }

    @Test
    fun `without credential_sets every query is required and yields one candidate`() {
        val query = dcql(listOf("pay", "age"))
        val out =
            resolver.resolve(
                query,
                listOf(match("pay", "cred-pay"), match("age", "cred-age")),
            )
        assertEquals(listOf(listOf("cred-pay", "cred-age")), out.credentialIds())
    }

    @Test
    fun `one-of set with both options satisfiable yields one candidate per option`() {
        val query =
            dcql(
                ids = listOf("sparkasse", "wero"),
                sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
            )
        val out =
            resolver.resolve(
                query,
                listOf(match("sparkasse", "cred-sparkasse"), match("wero", "cred-wero")),
            )
        assertEquals(listOf(listOf("cred-sparkasse"), listOf("cred-wero")), out.credentialIds())
    }

    @Test
    fun `one-of set with only one option satisfiable yields that option only`() {
        val query =
            dcql(
                ids = listOf("sparkasse", "wero"),
                sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
            )
        val out = resolver.resolve(query, listOf(match("wero", "cred-wero")))
        assertEquals(listOf(listOf("cred-wero")), out.credentialIds())
    }

    @Test
    fun `required set with no satisfiable option yields nothing at all`() {
        val query =
            dcql(
                ids = listOf("sparkasse", "wero"),
                sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
            )
        assertTrue(resolver.resolve(query, emptyList()).isEmpty())
    }

    @Test
    fun `two stored credentials matching one query yield two candidates`() {
        val query =
            dcql(
                ids = listOf("sparkasse", "wero"),
                sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
            )
        val out =
            resolver.resolve(
                query,
                listOf(
                    match("sparkasse", "cred-sparkasse-a"),
                    match("sparkasse", "cred-sparkasse-b"),
                ),
            )
        assertEquals(
            listOf(listOf("cred-sparkasse-a"), listOf("cred-sparkasse-b")),
            out.credentialIds(),
        )
    }

    @Test
    fun `an option naming two query ids produces one candidate holding both`() {
        val query = dcql(
            ids = listOf("card", "age"),
            sets = listOf(set(listOf("card", "age"))),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("age", "cred-age")),
        )
        assertEquals(listOf(listOf("cred-card", "cred-age")), out.credentialIds())
    }

    @Test
    fun `two required sets produce the cartesian product across them`() {
        val query = dcql(
            ids = listOf("sparkasse", "wero", "age"),
            sets = listOf(
                set(listOf("sparkasse"), listOf("wero")),
                set(listOf("age")),
            ),
        )
        val out = resolver.resolve(
            query,
            listOf(
                match("sparkasse", "cred-sparkasse"),
                match("wero", "cred-wero"),
                match("age", "cred-age"),
            ),
        )
        assertEquals(
            listOf(
                listOf("cred-sparkasse", "cred-age"),
                listOf("cred-wero", "cred-age"),
            ),
            out.credentialIds(),
        )
    }

    @Test
    fun `an optional set is never disclosed`() {
        val query = dcql(
            ids = listOf("card", "loyalty"),
            sets = listOf(
                set(listOf("card")),
                set(listOf("loyalty"), required = false),
            ),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("loyalty", "cred-loyalty")),
        )
        assertEquals(listOf(listOf("cred-card")), out.credentialIds())
    }

    @Test
    fun `a request whose sets are all optional yields nothing`() {
        val query = dcql(
            ids = listOf("loyalty"),
            sets = listOf(set(listOf("loyalty"), required = false)),
        )
        assertTrue(resolver.resolve(query, listOf(match("loyalty", "cred-loyalty"))).isEmpty())
    }

    @Test
    fun `a credential query referenced by no set is not requested`() {
        val query = dcql(
            ids = listOf("card", "stray"),
            sets = listOf(set(listOf("card"))),
        )
        val out = resolver.resolve(
            query,
            listOf(match("card", "cred-card"), match("stray", "cred-stray")),
        )
        assertEquals(listOf(listOf("cred-card")), out.credentialIds())
    }

    @Test
    fun `candidate order follows option order then wallet order`() {
        val query = dcql(
            ids = listOf("wero", "sparkasse"),
            sets = listOf(set(listOf("sparkasse"), listOf("wero"))),
        )
        val out = resolver.resolve(
            query,
            listOf(
                match("wero", "cred-wero"),
                match("sparkasse", "cred-sparkasse-a"),
                match("sparkasse", "cred-sparkasse-b"),
            ),
        )
        // Option order wins over the order matches arrived in: the sparkasse option is
        // listed first, and within it the wallet's own credential order is preserved.
        assertEquals(
            listOf(listOf("cred-sparkasse-a"), listOf("cred-sparkasse-b"), listOf("cred-wero")),
            out.credentialIds(),
        )
    }

    @Test
    fun `the candidate count is capped`() {
        val capped = DcqlCandidateResolver(maxCandidates = 3)
        val query = dcql(ids = listOf("card"))
        val out = capped.resolve(
            query,
            (1..10).map { match("card", "cred-$it") },
        )
        assertEquals(3, out.size)
        assertEquals(listOf(listOf("cred-1"), listOf("cred-2"), listOf("cred-3")), out.credentialIds())
    }
}
