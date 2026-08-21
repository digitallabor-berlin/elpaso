package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.vct.VctDisplay
import dev.digitallabor.elpaso.wallet.vct.VctImage
import dev.digitallabor.elpaso.wallet.vct.VctLogo
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import dev.digitallabor.elpaso.wallet.vct.VctRendering
import dev.digitallabor.elpaso.wallet.vct.VctSimpleRendering
import eu.europa.ec.eudi.openid4vci.Display

/**
 * Converts the EUDI library's [Display] blocks from a credential configuration's issuer
 * metadata (`credential_configurations_supported[<id>].display`) into the JSON shape that
 * [dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay] reads.
 *
 * Same wire format as SD-JWT VC Type Metadata so SD-JWT VC and mso_mdoc credentials share
 * a single rendering path. Returns `"{}"` when the list is empty so the UI can fall back
 * to its deterministic palette.
 */
internal object IssuerDisplayJsonBuilder {

    fun build(displays: List<Display>): String {
        if (displays.isEmpty()) return "{}"
        val metadata = VctMetadata(
            name = displays.first().name,
            description = displays.first().description,
            display = displays.map { it.toVctDisplay() },
        )
        return runCatching {
            HttpClientFactory.json.encodeToString(VctMetadata.serializer(), metadata)
        }.getOrDefault("{}")
    }

    private fun Display.toVctDisplay(): VctDisplay {
        val logoUri = logo?.uri?.toString()
        val rendering = if (
            logoUri != null ||
            backgroundColor != null ||
            textColor != null ||
            backgroundImage != null
        ) {
            VctRendering(
                simple = VctSimpleRendering(
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    backgroundImage = backgroundImage?.toString()?.let { VctImage(uri = it) },
                    logo = logoUri?.let { VctLogo(uri = it, altText = logo?.alternativeText) },
                ),
            )
        } else null
        return VctDisplay(
            locale = locale?.toLanguageTag(),
            name = name,
            description = description,
            rendering = rendering,
        )
    }
}
