package dev.digitallabor.elpaso.wallet.domain.model

import androidx.compose.ui.graphics.Color
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.vct.VctDisplay
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import java.util.Locale

/**
 * Locale-resolved view of an SD-JWT VC's Type Metadata. Computed on the fly from the
 * persisted [Credential.displayMetadataJson] — never stored separately. For mso_mdoc or
 * for SD-JWTs where metadata fetch failed, the JSON is `"{}"` and we fall back to the
 * stored [Credential.displayName].
 */
data class CredentialDisplay(
    val name: String,
    val description: String?,
    val backgroundColor: Color?,
    val textColor: Color?,
    val logoUri: String?,
    val backgroundImageUri: String?,
) {
    companion object {
        fun resolve(credential: Credential, locale: Locale = Locale.getDefault()): CredentialDisplay =
            resolve(credential.displayMetadataJson, credential.displayName, locale)

        /**
         * Pure resolver used both at issuance time (before a [Credential] exists) and for
         * persisted credentials. [fallbackName] is the display name from the credential
         * configuration; returned when no `display` block can be picked.
         */
        fun resolve(
            displayMetadataJson: String,
            fallbackName: String,
            locale: Locale = Locale.getDefault(),
        ): CredentialDisplay {
            val metadata = parse(displayMetadataJson)
            val block = pickDisplayBlock(metadata, locale)
            val simple = block?.rendering?.simple
            val name = block?.name ?: metadata?.name ?: fallbackName
            val description = block?.description ?: metadata?.description
            return CredentialDisplay(
                name = name,
                description = description?.takeIf { it.isNotBlank() },
                backgroundColor = simple?.backgroundColor?.let(::parseHexColor),
                textColor = simple?.textColor?.let(::parseHexColor),
                logoUri = simple?.logo?.uri,
                backgroundImageUri = simple?.backgroundImage?.uri,
            )
        }

        private fun parse(json: String): VctMetadata? {
            if (json.isBlank() || json == "{}") return null
            return runCatching {
                HttpClientFactory.json.decodeFromString(VctMetadata.serializer(), json)
            }.getOrNull()
        }

        private fun pickDisplayBlock(metadata: VctMetadata?, locale: Locale): VctDisplay? {
            val display = metadata?.display ?: return null
            if (display.isEmpty()) return null
            val tag = locale.toLanguageTag()
            val language = locale.language
            return display.firstOrNull { it.locale.equals(tag, ignoreCase = true) }
                ?: display.firstOrNull { it.locale?.substringBefore('-').equals(language, ignoreCase = true) }
                ?: display.firstOrNull { it.locale.isNullOrBlank() }
                ?: display.first()
        }

        private fun parseHexColor(raw: String): Color? {
            val hex = raw.trim().removePrefix("#")
            val normalized = when (hex.length) {
                6 -> "FF$hex"
                8 -> hex
                else -> return null
            }
            val value = normalized.toLongOrNull(16) ?: return null
            return Color(value)
        }
    }
}
