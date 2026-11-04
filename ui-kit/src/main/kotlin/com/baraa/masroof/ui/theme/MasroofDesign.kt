package com.baraa.masroof.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

/** Version of the approved design shown in the design catalog. */
object MasroofDesign {
    const val version = "approved-2026-10-10"
}

/** One geometry and visual policy for all contextual explanations. */
object MasroofGuidanceStyle {
    val iconSize = MasroofIconSizes.md
    val maxWidth = 320.dp
    val contentPadding = MasroofSpacing.cardInnerPadding
    val anchorGap = MasroofSpacing.inlineGap
    val windowMargin = MasroofSpacing.screenHorizontal
    val shape = MasroofShapes.small
    val elevation = 6.dp
    /** First-run tour: dim level of the screen around the lit-up target. */
    const val tourScrimAlpha = 0.6f
    val tourSpotlightPadding = MasroofSpacing.smallGap
    val tourSpotlightRadius = 12.dp
    val backgroundColor: androidx.compose.ui.graphics.Color
        @androidx.compose.runtime.Composable get() =
            if (masroofIsDark()) {
                MasroofDarkColors.SurfaceContainerHigh
            } else {
                MasroofLightColors.Surface
            }
    val borderColor: androidx.compose.ui.graphics.Color
        @androidx.compose.runtime.Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
}

/** Component geometry outside the spacing rhythm (charts, sheets and badges). */
object MasroofSizes {
    val size18 = 18.dp
    val size22 = 22.dp
    val size56 = 56.dp
    val size64 = 64.dp
    val size180 = 180.dp
}

object MasroofFontWeights {
    val normal = FontWeight.Normal
    val medium = FontWeight.Medium
    val semiBold = FontWeight.SemiBold
    val bold = FontWeight.Bold
}

object MasroofOpacity {
    const val badge = 0.14f
    const val divider = 0.25f
    const val subtle = 0.35f
    const val muted = 0.45f
}
