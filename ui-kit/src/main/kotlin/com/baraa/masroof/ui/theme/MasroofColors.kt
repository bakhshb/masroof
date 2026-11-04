package com.baraa.masroof.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object MasroofPalette {
    val Primary = Color(0xFF0F766E)
    val Account = Color(0xFF1743A3)
    val AccountSoft = Color(0xFFE8EFFF)
    val Card = Color(0xFF7C3AED)
    val CardSoft = Color(0xFFF5F3FF)
    val Liability = Color(0xFFB45309)
    val LiabilitySoft = Color(0xFFFFFBEB)
    val Inflow = Color(0xFF047857)
    val Outflow = Color(0xFFB91C1C)
    val Text = Color(0xFF1A1F24)
    val Muted = Color(0xFF6B7280)
    val Border = Color(0xFFE5E7EB)
}

object MasroofLightColors {
    val Background = Color(0xFFF4F6F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceDim = Color(0xFFEEF2F5)
    val SurfaceBright = Color(0xFFFFFFFF)
    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF7F9FA)
    val SurfaceContainer = Color(0xFFF4F6F8)
    val SurfaceContainerHigh = Color(0xFFEEF2F5)
    val SurfaceContainerHighest = Color(0xFFE8EEF3)
    val OnSurface = MasroofPalette.Text
    val OnSurfaceVariant = MasroofPalette.Muted
    val Outline = MasroofPalette.Border
    val OutlineVariant = Color(0xFFE5E7EB)
    val Primary = MasroofPalette.Primary
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = MasroofReference.navLight
    val OnPrimaryContainer = Color(0xFF042F2E)
    val SecondaryContainer = MasroofPalette.CardSoft
    val OnSecondaryContainer = MasroofPalette.Card
    val Tertiary = MasroofPalette.Inflow
    val OnTertiary = Color(0xFFFFFFFF)
    val Error = MasroofPalette.Outflow
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF410002)
    val SurfaceVariant = Color(0xFFE8EEF3)
}

object MasroofDarkColors {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1E1E1E)
    val SurfaceDim = Color(0xFF0F0F0F)
    val SurfaceBright = Color(0xFF1A1A1A)
    val SurfaceContainerLowest = Color(0xFF0F0F0F)
    val SurfaceContainerLow = Color(0xFF1A1A1A)
    val SurfaceContainer = Color(0xFF1E1E1E)
    val SurfaceContainerHigh = Color(0xFF282828)
    val SurfaceContainerHighest = Color(0xFF323232)
    val OnSurface = Color(0xFFE3E3E3)
    val OnSurfaceVariant = Color(0xFFA8A8A8)
    val Outline = MasroofReference.borderDark
    val OutlineVariant = Color(0xFF323232)
    val Primary = Color(0xFF5EBDB2)
    val OnPrimary = Color(0xFF042F2E)
    val PrimaryContainer = Color(0xFF0F766E)
    val OnPrimaryContainer = Color(0xFFCCFBF1)
    val SecondaryContainer = Color(0xFF2E1065)
    val OnSecondaryContainer = Color(0xFFE9D5FF)
    val Tertiary = Color(0xFF6EE7B7)
    val OnTertiary = Color(0xFF064E3B)
    val Error = Color(0xFFFCA5A5)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFB4AB)
    val SurfaceVariant = SurfaceContainer
}

@Immutable
data class MasroofExtendedColors(
    val account: Color,
    val accountSoft: Color,
    val card: Color,
    val cardSoft: Color,
    val liability: Color,
    val liabilitySoft: Color,
    val inflow: Color,
    val inflowSoft: Color,
    val outflow: Color,
    val outflowSoft: Color,
    val miniBackground: Color,
    val hintBackground: Color,
    val cardSurface: Color,
    val navCardSurface: Color,
    val navCardBorder: Color,
    val cardBorder: Color,
)

val LocalMasroofExtendedColors = staticCompositionLocalOf { lightExtendedColors() }

fun lightExtendedColors() = MasroofExtendedColors(
    account = MasroofPalette.Account,
    accountSoft = MasroofPalette.AccountSoft,
    card = MasroofPalette.Card,
    cardSoft = MasroofPalette.CardSoft,
    liability = MasroofPalette.Liability,
    liabilitySoft = MasroofPalette.LiabilitySoft,
    inflow = MasroofPalette.Inflow,
    inflowSoft = Color(0xFFF0FDF4),
    outflow = MasroofPalette.Outflow,
    outflowSoft = Color(0xFFFEF2F2),
    miniBackground = Color(0xFFF8FAFC),
    hintBackground = Color(0xFFF8FAFC),
    cardSurface = MasroofLightColors.Surface,
    navCardSurface = MasroofLightColors.Surface,
    navCardBorder = Color(0xFFE5E7EB),
    cardBorder = Color(0xFFE5E7EB),
)

fun darkExtendedColors() = MasroofExtendedColors(
    account = MasroofReference.accountChartDark,
    accountSoft = Color(0xFF1A2744),
    card = MasroofReference.creditChartDark,
    cardSoft = Color(0xFF2E1065),
    liability = Color(0xFFFCD34D),
    liabilitySoft = Color(0xFF422006),
    inflow = Color(0xFF6EE7B7),
    inflowSoft = Color(0xFF0F3D2E),
    outflow = Color(0xFFFCA5A5),
    outflowSoft = Color(0xFF4A1515),
    miniBackground = MasroofDarkColors.SurfaceContainerLow,
    hintBackground = MasroofDarkColors.SurfaceContainerLow,
    cardSurface = MasroofDarkColors.SurfaceContainer,
    navCardSurface = MasroofDarkColors.SurfaceContainer,
    navCardBorder = MasroofReference.borderDark,
    cardBorder = MasroofReference.borderDark,
)
