package dev.digitallabor.elpaso.wallet.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visual treatment for a credential card.
 *
 * Prefers issuer-supplied colors from the SD-JWT VC Type Metadata (`rendering.simple`),
 * resolved via [CredentialDisplay]. Falls back to a deterministic hash-derived palette
 * so mso_mdoc and metadata-less SD-JWT credentials still look distinct.
 *
 * Cards render two layered brushes: a diagonal [baseGradient] for depth and a vertical
 * white [sheenOverlay] for a glossy highlight near the top.
 */
data class PassArt(
    val gradientStart: Color,
    val gradientEnd: Color,
    val foreground: Color,
    val accent: Color,
) {
    val baseGradient: Brush =
        Brush.linearGradient(
            colors = listOf(gradientStart, gradientEnd),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    val sheenOverlay: Brush =
        Brush.verticalGradient(
            colorStops =
                arrayOf(
                    0f to Color.White.copy(alpha = 0.30f),
                    0.45f to Color.White.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
        )

    companion object {
        private val PALETTES =
            listOf(
                PassArt(Color(0xFF1A237E), Color(0xFF3949AB), Color.White, Color(0xFFFFD54F)),
                PassArt(Color(0xFF263238), Color(0xFF455A64), Color.White, Color(0xFF80DEEA)),
                PassArt(Color(0xFF1B5E20), Color(0xFF388E3C), Color.White, Color(0xFFFFF59D)),
                PassArt(Color(0xFF4A148C), Color(0xFF7B1FA2), Color.White, Color(0xFFF8BBD0)),
                PassArt(Color(0xFFBF360C), Color(0xFFE64A19), Color.White, Color(0xFFFFE0B2)),
                PassArt(Color(0xFF004D40), Color(0xFF00796B), Color.White, Color(0xFFB2DFDB)),
            )

        fun forCredential(credential: Credential): PassArt {
            fromDisplay(CredentialDisplay.resolve(credential))?.let { return it }
            val typeMatch =
                when {
                    credential.configurationId.contains("pid", ignoreCase = true) -> PALETTES[0]
                    credential.configurationId.contains("mdl", ignoreCase = true) -> PALETTES[1]
                    credential.configurationId.contains("diploma", ignoreCase = true) -> PALETTES[2]
                    else -> null
                }
            if (typeMatch != null) return typeMatch
            val idx = (
                credential.issuerId
                    .hashCode()
                    .rem(PALETTES.size)
                    .let { if (it < 0) it + PALETTES.size else it }
            )
            return PALETTES[idx]
        }

        /**
         * Issuance-preview variant: pre-existing [Credential] not yet available. Resolves
         * the issuer-supplied colors when present, otherwise falls back to a palette keyed
         * off [paletteSeed] (e.g. configuration id) so different offered credentials in
         * the same consent dialog still look distinct.
         */
        fun forDisplay(
            display: CredentialDisplay,
            paletteSeed: String,
        ): PassArt {
            fromDisplay(display)?.let { return it }
            val idx = (
                paletteSeed
                    .hashCode()
                    .rem(PALETTES.size)
                    .let { if (it < 0) it + PALETTES.size else it }
            )
            return PALETTES[idx]
        }

        private fun fromDisplay(display: CredentialDisplay): PassArt? {
            val bg = display.backgroundColor ?: return null
            val fg = display.textColor ?: return null
            return PassArt(
                gradientStart = bg,
                gradientEnd = bg.darken(0.40f),
                foreground = fg,
                accent = fg.copy(alpha = 0.4f),
            )
        }

        private fun Color.darken(factor: Float): Color {
            val k = 1f - factor.coerceIn(0f, 1f)
            return Color(red = red * k, green = green * k, blue = blue * k, alpha = alpha)
        }
    }
}
