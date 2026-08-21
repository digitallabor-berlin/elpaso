package dev.digitallabor.elpaso.wallet.presentation

import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.vct.SdJwtVctExtractor
import eu.europa.ec.eudi.openid4vp.OpenId4VPSpec
import eu.europa.ec.eudi.openid4vp.dcql.ClaimPathElement
import eu.europa.ec.eudi.openid4vp.dcql.CredentialQuery
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.dcql.QueryId
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Matches stored credentials against a DCQL query.
 *
 * Implements the subset needed for a single-credential-per-query wallet:
 * - format filter (mso_mdoc / dc+sd-jwt),
 * - SD-JWT VC `vct_values` filter (from the `meta` block),
 * - mDoc `doctype_value` filter.
 *
 * `credential_sets` (combinatorial queries across credentials) are not yet honoured —
 * we surface every credential that matches any single CredentialQuery.
 */
class DcqlMatcher {

    data class Match(
        val queryId: String,
        val credentialId: String,
        val format: Format,
        val requestedClaimPaths: List<List<String>>,
        val intentToRetain: Map<List<String>, Boolean>,
    )

    fun match(query: DCQL?, credentials: List<Credential>): List<Match> {
        if (query == null) return emptyList()
        val out = mutableListOf<Match>()
        for (cq in query.credentials.value) {
            val format = mapFormat(cq) ?: continue
            val metaCandidates = metaIdentifiers(cq, format)
            val paths = (cq.claims ?: emptyList()).map { it.path.value.toStringList() }
                .filter { it.isNotEmpty() }
            val intents = (cq.claims ?: emptyList()).associate {
                it.path.value.toStringList() to (it.intentToRetain ?: false)
            }
            for (c in credentials) {
                if (c.format != format) continue
                if (metaCandidates.isNotEmpty()) {
                    val credentialIdentifier = identifierFor(c)
                    if (credentialIdentifier == null || metaCandidates.none { it == credentialIdentifier }) continue
                }
                out += Match(
                    queryId = cq.id.value,
                    credentialId = c.id,
                    format = format,
                    requestedClaimPaths = paths,
                    intentToRetain = intents,
                )
            }
        }
        return out
    }

    fun queryIdFor(match: Match): QueryId = QueryId(match.queryId)

    private fun mapFormat(cq: CredentialQuery): Format? = when (cq.format.value) {
        OpenId4VPSpec.FORMAT_SD_JWT_VC -> Format.SdJwtVc
        OpenId4VPSpec.FORMAT_MSO_MDOC -> Format.MsoMdoc
        else -> null
    }

    private fun metaIdentifiers(cq: CredentialQuery, format: Format): List<String> = when (format) {
        Format.SdJwtVc -> cq.meta[OpenId4VPSpec.DCQL_SD_JWT_VC_VCT_VALUES]
            ?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        Format.MsoMdoc -> listOfNotNull(
            cq.meta[OpenId4VPSpec.DCQL_MSO_MDOC_DOCTYPE_VALUE]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun List<ClaimPathElement>.toStringList(): List<String> = mapNotNull {
        when (it) {
            is ClaimPathElement.Claim -> it.name
            else -> null
        }
    }

    /**
     * For SD-JWT VC, the value to match against `vct_values` is the `vct` claim inside the
     * issuer-signed JWT — not the OpenID4VCI configuration id. We decode the issuer JWT
     * payload and extract it on the fly. For mdoc, `doctype_value` is matched against the
     * stored `configurationId` (which the issuance flow currently fills with the doctype).
     */
    private fun identifierFor(c: Credential): String? = when (c.format) {
        Format.SdJwtVc -> SdJwtVctExtractor.extract(c.payload)
        Format.MsoMdoc -> c.configurationId
    }
}
