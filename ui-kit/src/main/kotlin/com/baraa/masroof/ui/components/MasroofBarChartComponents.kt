package com.baraa.masroof.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.theme.MasroofShapes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.math.BigDecimal
import java.math.RoundingMode

enum class MasroofHorizontalBarStyle {
    Default,
    Inflow,
    Outflow,
}

object MasroofBarChart {
    fun progress(value: BigDecimal, max: BigDecimal): Float {
        if (max.signum() <= 0) return 0f
        return value.max(BigDecimal.ZERO)
            .divide(max, 4, RoundingMode.HALF_UP)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

@Composable
fun MasroofHorizontalBar(
    progress: Float,
    modifier: Modifier = Modifier,
    style: MasroofHorizontalBarStyle = MasroofHorizontalBarStyle.Default,
) {
    val extended = MasroofThemeExtras.extendedColors
    val fillColor = when (style) {
        MasroofHorizontalBarStyle.Default -> MaterialTheme.colorScheme.primary
        MasroofHorizontalBarStyle.Inflow -> extended.inflow
        MasroofHorizontalBarStyle.Outflow -> extended.outflow
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val clampedProgress = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MasroofSpacing.accentBarHeight)
            .background(trackColor, MasroofShapes.small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clampedProgress)
                .height(MasroofSpacing.accentBarHeight)
                .background(fillColor, MasroofShapes.small),
        )
    }
}
