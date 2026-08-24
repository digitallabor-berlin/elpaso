package dev.digitallabor.elpaso.wallet.presentation

import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId

/**
 * One complete, spec-valid way to satisfy a DCQL request: a concrete credential chosen
 * for every credential query the wallet must answer. The UI renders one of these per
 * carousel page, so the page the user is looking at *is* the selection.
 */
data class PresentationCandidate(
    val assignments: List<DcqlMatcher.Match>,
)

/**
 * Evaluates OpenID4VP 1.0 §6.4.2 (Selecting Credentials) over matches that
 * [DcqlMatcher] has already computed.
 *
 * Deliberately consumes [DcqlMatcher.Match] rather than credentials: the matcher reaches
 * `android.util.Base64` (via `SdJwtVctExtractor` → `B64u`) and therefore cannot be
 * unit-tested on the JVM. Keeping set evaluation here — where no credential bytes are
 * touched — is what makes the §6.4.2 MUST testable. Do not add credential decoding.
 */
class DcqlCandidateResolver(
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    fun resolve(
        query: DCQL?,
        matches: List<DcqlMatcher.Match>,
    ): List<PresentationCandidate> {
        if (query == null) return emptyList()

        val byQuery: Map<String, List<DcqlMatcher.Match>> = matches.groupBy { it.queryId }
        val queryOrder: List<String> = query.credentials.value.map { it.id.value }

        // Each required set contributes its options; an option is a list of query ids.
        // §6.4.2: with no credential_sets, every credential query is requested. With
        // credential_sets, only the required sets must be satisfied — optional sets are
        // dropped entirely so the wallet never volunteers a credential, and credential
        // queries not referenced by any set are not part of the request.
        val requiredSets: List<List<List<String>>> =
            when (val sets = query.credentialSets) {
                null -> {
                    listOf(listOf(queryOrder))
                }

                else -> {
                    sets.value
                        .filter { it.requiredOrDefault }
                        .map { set -> set.options.map { option -> option.value.map(QueryId::value) } }
                }
            }
        // Every set optional means nothing is required; presenting anything would be
        // volunteering it. Without this guard the empty product below yields one bogus
        // empty candidate that would report success while disclosing nothing.
        if (requiredSets.isEmpty()) return emptyList()

        // Per required set, every concrete assignment satisfying one of its options.
        val perSet: List<List<Map<String, DcqlMatcher.Match>>> =
            requiredSets.map { options ->
                val satisfiable =
                    options.filter { option ->
                        option.all { id -> !byQuery[id].isNullOrEmpty() }
                    }
                // §6.4.2: "If the Wallet cannot deliver all non-optional Credentials
                // requested by the Verifier according to these rules, it MUST NOT return
                // any Credential(s)."
                if (satisfiable.isEmpty()) return emptyList()
                satisfiable.flatMap { option -> expand(option, byQuery) }
            }

        var combos: List<Map<String, DcqlMatcher.Match>> = listOf(emptyMap())
        for (setAssignments in perSet) {
            val next = mutableListOf<Map<String, DcqlMatcher.Match>>()
            for (accumulated in combos) {
                for (assignment in setAssignments) {
                    merge(accumulated, assignment)?.let { next += it }
                }
            }
            if (next.isEmpty()) return emptyList()
            // Bound the intermediate product. Results are in verifier-preference order,
            // so truncating keeps the most-preferred combinations.
            combos = next.take(MAX_INTERMEDIATE_COMBINATIONS)
        }

        return combos
            .map { assignment ->
                PresentationCandidate(assignments = queryOrder.mapNotNull { assignment[it] })
            }.filter { it.assignments.isNotEmpty() }
            .distinctBy { candidate -> candidate.assignments.map { "${it.queryId}:${it.credentialId}" } }
            .take(maxCandidates)
    }

    /** Cartesian product of the credentials available for each query id in [option]. */
    private fun expand(
        option: List<String>,
        byQuery: Map<String, List<DcqlMatcher.Match>>,
    ): List<Map<String, DcqlMatcher.Match>> {
        var accumulated: List<Map<String, DcqlMatcher.Match>> = listOf(emptyMap())
        for (id in option) {
            val choices = byQuery[id].orEmpty()
            accumulated = accumulated.flatMap { partial -> choices.map { partial + (id to it) } }
        }
        return accumulated
    }

    /**
     * Merge two per-set assignments, or null when they disagree about which credential
     * answers a shared query id. A query id may appear in options of two different sets.
     */
    private fun merge(
        a: Map<String, DcqlMatcher.Match>,
        b: Map<String, DcqlMatcher.Match>,
    ): Map<String, DcqlMatcher.Match>? {
        for ((id, match) in b) {
            val existing = a[id] ?: continue
            if (existing.credentialId != match.credentialId) return null
        }
        return a + b
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES: Int = 32
        private const val MAX_INTERMEDIATE_COMBINATIONS: Int = 512
    }
}
