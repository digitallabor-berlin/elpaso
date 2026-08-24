package dev.digitallabor.elpaso.wallet.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme. Colour comes from the system: Material You dynamic colour (wallpaper-derived)
 * on API 31+, and the Material 3 baseline scheme below that, where no wallpaper extraction
 * exists. There is no wallet-specific palette and no colour-theme switcher — only the
 * light/dark choice, which [darkTheme] carries.
 */
@Composable
fun ElPasoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                darkColorScheme()
            }

            else -> {
                lightColorScheme()
            }
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        content = content,
    )
}
