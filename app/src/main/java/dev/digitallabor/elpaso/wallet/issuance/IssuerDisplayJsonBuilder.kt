package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.vct.VctClaim
import dev.digitallabor.elpaso.wallet.vct.VctClaimDisplay
import dev.digitallabor.elpaso.wallet.vct.VctDisplay
import dev.digitallabor.elpaso.wallet.vct.VctImage
import dev.digitallabor.elpaso.wallet.vct.VctLogo
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import dev.digitallabor.elpaso.wallet.vct.VctRendering
import dev.digitallabor.elpaso.wallet.vct.VctSimpleRendering
import eu.europa.ec.eudi.openid4vci.Claim
import eu.europa.ec.eudi.openid4vci.ClaimPath
import eu.europa.ec.eudi.openid4vci.ClaimPathElement
import eu.europa.ec.eudi.openid4vci.Display
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a credential configuration's issuer metadata
 * (`credential_configurations_supported[<id>]`) into the JSON shape that
 * [dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay] and
 * [dev.digitallabor.elpaso.wallet.domain.claims.ClaimLabelResolver] read: the [Display]
 * blocks become card rendering, the [Claim] list becomes per-claim labels.
 *
 * Same wire format as SD-JWT VC Type Metadata so SD-JWT VC and mso_mdoc credentials share
 * a single rendering path. Returns `"{}"` when there is nothing to say so the UI can fall
 * back to its deterministic palette and to raw claim paths.
 */
internal object IssuerDisplayJsonBuilder {
    fun build(
        displays: List<Display>,
        claims: List<Claim> = emptyList(),
    ): String {
        if (displays.isEmpty() && claims.isEmpty()) return "{}"
        val metadata =
            VctMetadata(
                name = displays.firstOrNull()?.name,
                description = displays.firstOrNull()?.description,
                display = displays.map { it.toVctDisplay() },
                claims = claims.mapNotNull { it.toVctClaim() },
            )
        return runCatching {
            HttpClientFactory.json.encodeToString(VctMetadata.serializer(), metadata)
        }.getOrDefault("{}")
    }

    /**
     * A claim with no display block carries no label, so it would only bloat the persisted
     * blob — drop it rather than store an entry the resolver will skip anyway. `mandatory`
     * is likewise omitted: nothing in the wallet renders it.
     */
    private fun Claim.toVctClaim(): VctClaim? {
        val labels =
            display.mapNotNull { d ->
                d.name?.takeIf { it.isNotBlank() }?.let {
                    VctClaimDisplay(locale = d.locale?.toLanguageTag(), name = it)
                }
            }
        if (labels.isEmpty()) return null
        return VctClaim(path = path.toJsonPath(), display = labels)
    }

    /**
     * Serializes a [ClaimPath] the way both OpenID4VCI and SD-JWT VC Type Metadata write
     * it: object keys as strings, array indices as integers, the all-elements wildcard as
     * JSON `null`.
     */
    private fun ClaimPath.toJsonPath(): List<JsonElement> =
        value.map { element ->
            when (element) {
                is ClaimPathElement.Claim -> JsonPrimitive(element.name)
                is ClaimPathElement.ArrayElement -> JsonPrimitive(element.index)
                ClaimPathElement.AllArrayElements -> JsonNull
            }
        }

    private fun Display.toVctDisplay(): VctDisplay {
        val logoUri = logo?.uri?.toString()
        val rendering =
            if (
                logoUri != null ||
                backgroundColor != null ||
                textColor != null ||
                backgroundImage != null
            ) {
                VctRendering(
                    simple =
                        VctSimpleRendering(
                            backgroundColor = backgroundColor,
                            textColor = textColor,
                            backgroundImage = backgroundImage?.toString()?.let { VctImage(uri = it) },
                            logo = logoUri?.let { VctLogo(uri = it, altText = logo?.alternativeText) },
                        ),
                )
            } else {
                null
            }
        return VctDisplay(
            locale = locale?.toLanguageTag(),
            name = name,
            description = description,
            rendering = rendering,
        )
    }
}
