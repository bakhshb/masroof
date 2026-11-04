package com.baraa.masroof.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Final computed geometry of the approved 411-wide reference, not Material defaults. */
object MasroofReference {
    val headerTop = 22.dp
    val headerHorizontal = 20.dp
    val headerBottom = 14.dp
    val sectionGap = 12.dp
    val sectionHeadingTop = 20.dp
    val sectionHeadingBottom = 10.dp
    val contextualBandPadding = 13.dp
    val heroFooterGap = 4.dp
    val metricGap = 10.dp
    val shortcutGap = 7.dp
    val shortcutHorizontal = 4.dp
    val shortcutVertical = 12.dp
    val shortcutMinWidth = 80.dp
    val shortcutSeparatorHeight = 66.dp
    val aboutLogo = 76.dp
    val icon = 19.dp
    val iconBox = 36.dp
    val iconButton = 38.dp
    val statusBadgeMaxWidth = 120.dp
    val inlineStatusMinWidth = 300.dp
    val compactScreenHeight = 700.dp
    val rowVertical = 12.dp
    val rowGap = 11.dp
    val metadataGap = 3.dp
    val heroPadding = 20.dp
    val chartHeadGap = 15.dp
    val heroFooterValue = TextStyle(fontFamily = MasroofFontFamily, fontSize = 18.sp,
        lineHeight = 28.8.sp, fontWeight = MasroofFontWeights.semiBold, fontFeatureSettings = "tnum")
    val navigationHorizontal = 8.dp
    val navigationItemVertical = 9.dp
    val navigationTop = 12.dp
    val navigationBottom = 16.dp
    val navigationGap = 5.dp
    val navigationItemPadding = 7.dp
    val notificationBadge = 16.dp
    val notificationOffsetX = 3.dp
    val notificationOffsetY = (-4).dp
    val lastRowBottom = 3.dp
    val chartHeight = 226.dp
    val chartLeft = 64.dp
    val chartRight = 14.dp
    val chartTop = 26.dp
    val chartBottom = 51.dp
    val chartStroke = 2.5.dp
    val chartPoint = 3.dp
    val selectedPoint = 6.dp
    val chartDash = 4.dp
    val chartDashStroke = 3.dp
    val averageLineStroke = 1.5.dp
    val averageLegendWidth = 22.dp
    val averageDash = 5.dp
    val scopeTabInset = 4.dp
    val scopeTabRadius = 9.dp
    val periodHeaderHorizontal = 8.dp
    val axisUnitLeft = 12.dp
    val axisUnitBaseline = 14.dp
    val axisLabelOffset = 10.dp
    val axisLabelBaselineOffset = 4.dp
    val dateBaselineOffset = 22.dp
    val axisDayBottom = 5.dp
    val unit = TextStyle(fontFamily = MasroofFontFamily, fontSize = 12.sp,
        lineHeight = 19.2.sp, fontWeight = MasroofFontWeights.normal)
    val brand = TextStyle(fontFamily = MasroofFontFamily, fontSize = 23.sp,
        lineHeight = 36.8.sp, letterSpacing = (-0.6).sp, fontWeight = MasroofFontWeights.bold)
    val metricValue = TextStyle(fontFamily = MasroofFontFamily, fontSize = 23.sp,
        lineHeight = 36.8.sp, fontWeight = MasroofFontWeights.bold, fontFeatureSettings = "tnum")
    val averageValue = TextStyle(fontFamily = MasroofFontFamily, fontSize = 21.sp,
        lineHeight = 33.6.sp, fontWeight = MasroofFontWeights.bold, fontFeatureSettings = "tnum")
    val notificationCount = TextStyle(fontFamily = MasroofFontFamily, fontSize = 10.sp,
        lineHeight = 16.sp, fontWeight = MasroofFontWeights.bold)
    val rowAmount = TextStyle(fontFamily = MasroofFontFamily, fontSize = 15.sp,
        lineHeight = 24.sp, fontWeight = MasroofFontWeights.bold, fontFeatureSettings = "tnum")
    val pair = TextStyle(fontFamily = MasroofFontFamily, fontSize = 13.sp,
        lineHeight = 20.8.sp, fontWeight = MasroofFontWeights.normal)
    val selectedAmount = metricValue
    val chartLabel = TextStyle(fontFamily = MasroofFontFamily, fontSize = 11.sp,
        lineHeight = 17.6.sp, textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr)
    val borderDark = Color(0xFF323232)
    val navLight = Color(0xFFE6F3F1)
    val navDark = Color(0xFF14312E)
    val accountChartDark = Color(0xFFA8CEFF)
    val accountChartLight = Color(0xFF1743A3)
    val creditChartLight = Color(0xFF7C3AED)
    val creditChartDark = Color(0xFFC4B5FD)

    @Composable
    fun chartColor(role: MasroofHeroRole): Color {
        val dark = masroofIsDark()
        return when (role) {
            MasroofHeroRole.Account -> if (dark) accountChartDark else accountChartLight
            MasroofHeroRole.Card -> if (dark) creditChartDark else creditChartLight
            else -> MaterialTheme.colorScheme.primary
        }
    }

    @Composable
    fun selectedNavigationColor(): Color =
        if (masroofIsDark()) navDark else navLight
}
