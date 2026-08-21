package dev.digitallabor.elpaso.wallet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object EuropaPalette {
    private val primary = Color(0xFF003399)
    private val onPrimary = Color.White
    private val primaryContainer = Color(0xFFDCE2FF)
    private val onPrimaryContainer = Color(0xFF001034)
    private val euYellow = Color(0xFFFFCC00)
    private val onEuYellow = Color(0xFF1A1300)
    private val tertiaryBlue = Color(0xFF1A4DB8)
    private val onTertiaryBlue = Color.White
    private val tertiaryContainerBlue = Color(0xFFD0DCFF)
    private val onTertiaryContainerBlue = Color(0xFF00174A)
    private val surfaceLight = Color(0xFFFAFBFF)
    private val onSurfaceLight = Color(0xFF0F1F47)
    private val surfaceVariantLight = Color(0xFFE2E8F5)
    private val onSurfaceVariantLight = Color(0xFF42476A)
    private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    private val surfaceContainerLowLight = Color(0xFFF1F4FE)
    private val surfaceContainerLight = Color(0xFFE8EEFB)
    private val surfaceContainerHighLight = Color(0xFFDCE5FA)
    private val surfaceContainerHighestLight = Color(0xFFCBD7F5)
    private val outlineLight = Color(0xFF4A65A8)
    private val outlineVariantLight = Color(0xFFAEBDDB)

    private val primaryDark = Color(0xFFB6C5FF)
    private val onPrimaryDark = Color(0xFF001A4D)
    private val primaryContainerDark = Color(0xFF00257A)
    private val onPrimaryContainerDark = Color(0xFFDCE2FF)
    private val tertiaryDark = Color(0xFFB6C5FF)
    private val onTertiaryDark = Color(0xFF00174A)
    private val tertiaryContainerDark = Color(0xFF0F3380)
    private val onTertiaryContainerDark = Color(0xFFD0DCFF)
    private val surfaceDark = Color(0xFF0A132E)
    private val onSurfaceDark = Color(0xFFE5EAFB)
    private val surfaceVariantDark = Color(0xFF323A5A)
    private val onSurfaceVariantDark = Color(0xFFC0C8E0)
    private val surfaceContainerLowestDark = Color(0xFF050A1F)
    private val surfaceContainerLowDark = Color(0xFF111B3A)
    private val surfaceContainerDark = Color(0xFF162247)
    private val surfaceContainerHighDark = Color(0xFF202D58)
    private val surfaceContainerHighestDark = Color(0xFF2B3A6B)
    private val outlineDark = Color(0xFF8A9AC8)
    private val outlineVariantDark = Color(0xFF3A4670)

    val light: ColorScheme =
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = euYellow,
            onSecondary = onEuYellow,
            secondaryContainer = Color(0xFFFFEFA8),
            onSecondaryContainer = Color(0xFF231A00),
            tertiary = tertiaryBlue,
            onTertiary = onTertiaryBlue,
            tertiaryContainer = tertiaryContainerBlue,
            onTertiaryContainer = onTertiaryContainerBlue,
            background = surfaceLight,
            onBackground = onSurfaceLight,
            surface = surfaceLight,
            onSurface = onSurfaceLight,
            surfaceVariant = surfaceVariantLight,
            onSurfaceVariant = onSurfaceVariantLight,
            surfaceContainerLowest = surfaceContainerLowestLight,
            surfaceContainerLow = surfaceContainerLowLight,
            surfaceContainer = surfaceContainerLight,
            surfaceContainerHigh = surfaceContainerHighLight,
            surfaceContainerHighest = surfaceContainerHighestLight,
            outline = outlineLight,
            outlineVariant = outlineVariantLight,
        )

    val dark: ColorScheme =
        darkColorScheme(
            primary = primaryDark,
            onPrimary = onPrimaryDark,
            primaryContainer = primaryContainerDark,
            onPrimaryContainer = onPrimaryContainerDark,
            secondary = euYellow,
            onSecondary = onEuYellow,
            secondaryContainer = Color(0xFF4A3A00),
            onSecondaryContainer = Color(0xFFFFEFA8),
            tertiary = tertiaryDark,
            onTertiary = onTertiaryDark,
            tertiaryContainer = tertiaryContainerDark,
            onTertiaryContainer = onTertiaryContainerDark,
            background = surfaceDark,
            onBackground = onSurfaceDark,
            surface = surfaceDark,
            onSurface = onSurfaceDark,
            surfaceVariant = surfaceVariantDark,
            onSurfaceVariant = onSurfaceVariantDark,
            surfaceContainerLowest = surfaceContainerLowestDark,
            surfaceContainerLow = surfaceContainerLowDark,
            surfaceContainer = surfaceContainerDark,
            surfaceContainerHigh = surfaceContainerHighDark,
            surfaceContainerHighest = surfaceContainerHighestDark,
            outline = outlineDark,
            outlineVariant = outlineVariantDark,
        )
}
