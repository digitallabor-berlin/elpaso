@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.digitallabor.elpaso.wallet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.digitallabor.elpaso.wallet.R

/**
 * Roboto Flex variable font as the M3 Expressive **brand** typeface.
 *
 * One TTF file ships with all axes — weight, optical size, width, grade, slant, plus the
 * advanced parametric axes. We surface the weights we actually use; Compose picks the
 * closest one when a `Text` requests a `FontWeight` and synthesises the difference, but
 * with the variable font present the materialised instance is far closer than the static
 * fallback Roboto.
 *
 * Each `Font` entry passes:
 * - `weight = …` so Compose's text matching knows the slot
 * - `FontVariation.weight(…)` so the variable font actually renders at that exact weight
 *   (otherwise Compose materialises the static default and bold-emboldens — uglier)
 * - `FontVariation.opticalSizing(…)` matched to the M3 type-scale size so the glyph
 *   shapes are tuned for that display size (thinner strokes at large display sizes,
 *   beefier at headline sizes, per the Roboto Flex opsz designer's intent).
 */
private val RobotoFlexFamily: FontFamily = FontFamily(
    Font(
        R.font.roboto_flex,
        weight = FontWeight.Normal,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(400),
            FontVariation.opticalSizing(36.sp),
        ),
    ),
    Font(
        R.font.roboto_flex,
        weight = FontWeight.Medium,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(500),
            FontVariation.opticalSizing(36.sp),
        ),
    ),
    Font(
        R.font.roboto_flex,
        weight = FontWeight.SemiBold,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(600),
            FontVariation.opticalSizing(28.sp),
        ),
    ),
    Font(
        R.font.roboto_flex,
        weight = FontWeight.Bold,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(700),
            FontVariation.opticalSizing(28.sp),
        ),
    ),
)

/**
 * Material 3 Expressive type scale.
 *
 * Splits typefaces along the M3 brand/plain seam:
 * - **Brand** (Roboto Flex) → Display + Headline. These are editorial moments; the
 *   variable axes let us push weight/opsz for expression without bundling separate TTFs.
 * - **Plain** (system Roboto via [FontFamily.Default]) → Title + Body + Label. Static
 *   defaults are optimised for readability at small sizes and pair with Roboto Flex by
 *   design (same letterforms, same metrics — no awkward seam).
 *
 * Sizes/line-heights are unchanged from the baseline because the M3 spec is explicit:
 * *"Avoid changing the type size; this can affect how components render and reflow."*
 * Weight bumps + tighter Display tracking deliver the "emphasized" set on top.
 */
internal val ExpressiveTypography: Typography = Typography().run {
    val base = this
    copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.5).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
        ),

        headlineLarge = base.headlineLarge.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = RobotoFlexFamily,
            fontWeight = FontWeight.Medium,
        ),

        // Title/Body/Label stay on the plain typeface (system Roboto) — better readability
        // at small sizes than Roboto Flex's display-tuned defaults. Emphasized weights
        // applied on top per M3 Expressive guidance.
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),

        bodyLarge = base.bodyLarge.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.25.sp,
        ),
        bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall,

        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}
