package com.baraa.masroof.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class MasroofHeroRole { Brand, Account, Card, Liability, Inflow, Outflow }

/** Paired surfaces and content colors keep the approved heroes readable in both modes. */
@Composable
fun approvedHeroColors(role: MasroofHeroRole): Pair<Color, Color> {
    return heroColors(role, masroofIsDark())
}

internal fun heroColors(role: MasroofHeroRole, dark: Boolean): Pair<Color, Color> =
    when (role) {
        MasroofHeroRole.Brand -> if (dark) MasroofDarkColors.Primary to MasroofDarkColors.OnPrimary else MasroofLightColors.Primary to MasroofLightColors.OnPrimary
        MasroofHeroRole.Account -> if (dark) Color(0xFF1A2744) to Color(0xFFDBEAFE) else Color(0xFF1743A3) to Color.White
        MasroofHeroRole.Card -> if (dark) Color(0xFF2E1065) to Color(0xFFE9D5FF) else Color(0xFF6D28D9) to Color.White
        MasroofHeroRole.Liability -> if (dark) Color(0xFF422006) to Color(0xFFFCD34D) else Color(0xFF92400E) to Color.White
        MasroofHeroRole.Inflow -> if (dark) Color(0xFF0F3D2E) to Color(0xFFBBF7D0) else Color(0xFF047857) to Color.White
        MasroofHeroRole.Outflow -> if (dark) Color(0xFF4A1515) to Color(0xFFFECACA) else Color(0xFFB91C1C) to Color.White
    }
