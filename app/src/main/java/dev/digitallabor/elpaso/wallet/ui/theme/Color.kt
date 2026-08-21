package dev.digitallabor.elpaso.wallet.ui.theme

import androidx.compose.ui.graphics.Color

// Fixed semantic greens that intentionally do NOT shift with the user's color theme.
// Used for VoP success badges and positive transaction amounts, where green-as-success
// carries accessibility weight independent of brand palette. Picked to meet 4.5:1
// contrast against both light and dark surfaces in either palette.
val SuccessGreenContainer = Color(0xFFD0F0C0)
val OnSuccessGreenContainer = Color(0xFF1B5E20)
val PositiveAmountText = Color(0xFF1B5E20)
