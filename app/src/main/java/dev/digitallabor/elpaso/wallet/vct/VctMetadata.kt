package dev.digitallabor.elpaso.wallet.vct

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Subset of the SD-JWT VC Type Metadata document (RFC draft). The same shape is reused
 * to persist `display` blocks coming from OpenID4VCI issuer metadata
 * (`credential_configurations_supported[<id>].display`) so SD-JWT VC and mso_mdoc
 * credentials share a single rendering path. Fields beyond the ones we render
 * (schema, extends, …) are tolerated by the shared Json instance
 * (ignoreUnknownKeys = true).
 */
@Serializable
data class VctMetadata(
    val vct: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val display: List<VctDisplay> = emptyList(),
    val claims: List<VctClaim> = emptyList(),
)

/**
 * Per-claim metadata. Only `path` and `display` are modelled — `sd` and `svg_id` are
 * rendering/selective-disclosure hints the wallet does not act on.
 *
 * `path` is a JSON array whose elements may be strings (object keys), integers (array
 * indices) or `null` (all array elements), per SD-JWT VC Type Metadata and OpenID4VCI
 * 1.0 alike; hence [JsonElement] rather than `String`.
 */
@Serializable
data class VctClaim(
    val path: List<JsonElement> = emptyList(),
    val display: List<VctClaimDisplay> = emptyList(),
)

/**
 * Localized claim label, modelled as the union of two dialects that both land in
 * [dev.digitallabor.elpaso.wallet.domain.model.Credential.displayMetadataJson]:
 *
 * - SD-JWT VC Type Metadata writes `{"lang": ..., "label": ...}`.
 * - OpenID4VCI 1.0 issuer metadata writes `{"locale": ..., "name": ...}`.
 *
 * Carrying all four keys keeps one persisted shape for both sources and makes the blob
 * round-trippable: a fetched Type Metadata document re-serialized through this class
 * comes back out spelled the way its issuer wrote it.
 */
@Serializable
data class VctClaimDisplay(
    val lang: String? = null,
    val locale: String? = null,
    val label: String? = null,
    val name: String? = null,
    val description: String? = null,
) {
    /** BCP47 tag under either spelling, Type Metadata's winning when both are present. */
    val localeTag: String? get() = lang ?: locale

    /** Human-readable label under either spelling. */
    val text: String? get() = label ?: name
}

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
