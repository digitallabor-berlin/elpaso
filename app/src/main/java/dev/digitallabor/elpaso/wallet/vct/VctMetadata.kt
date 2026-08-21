package dev.digitallabor.elpaso.wallet.vct

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the SD-JWT VC Type Metadata document (RFC draft). The same shape is reused
 * to persist `display` blocks coming from OpenID4VCI issuer metadata
 * (`credential_configurations_supported[<id>].display`) so SD-JWT VC and mso_mdoc
 * credentials share a single rendering path. Fields beyond the ones we render
 * (schema, claims, extends, …) are tolerated by the shared Json instance
 * (ignoreUnknownKeys = true).
 */
@Serializable
data class VctMetadata(
    val vct: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val display: List<VctDisplay> = emptyList(),
)

@Serializable
data class VctDisplay(
    val locale: String? = null,
    val name: String? = null,
    val description: String? = null,
    val rendering: VctRendering? = null,
)

@Serializable
data class VctRendering(
    val simple: VctSimpleRendering? = null,
)

@Serializable
data class VctSimpleRendering(
    @SerialName("background_color") val backgroundColor: String? = null,
    @SerialName("text_color") val textColor: String? = null,
    @SerialName("background_image") val backgroundImage: VctImage? = null,
    val logo: VctLogo? = null,
)

@Serializable
data class VctLogo(
    val uri: String,
    @SerialName("uri#integrity") val integrity: String? = null,
    @SerialName("alt_text") val altText: String? = null,
)

/**
 * Background-image reference. Matches the OpenID4VCI shape
 * (`{"uri": "...", "alt_text": "..."}`).
 */
@Serializable
data class VctImage(
    val uri: String,
    @SerialName("alt_text") val altText: String? = null,
)
