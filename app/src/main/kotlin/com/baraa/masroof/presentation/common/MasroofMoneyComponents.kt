package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.theme.MasroofFontWeights
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofRowShape
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun MasroofMoneyRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: MasroofMoneyRowStyle = MasroofMoneyRowStyle.Neutral,
) {
    val colors = MasroofThemeExtras.extendedColors
    val color = when (style) {
        MasroofMoneyRowStyle.Inflow -> colors.inflow
        MasroofMoneyRowStyle.Outflow -> colors.outflow
        MasroofMoneyRowStyle.Highlight -> colors.liability
        MasroofMoneyRowStyle.Neutral -> MaterialTheme.colorScheme.onSurface
    }
    MasroofDetailRow(label, value, modifier, valueColor = color)
}

@Composable
fun MasroofHintBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    val extended = MasroofThemeExtras.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MasroofRowShape,
        color = extended.hintBackground,
        border = androidx.compose.foundation.BorderStroke(
            width = MasroofSpacing.borderWidth,
            color = MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(
            text,
            modifier = Modifier.padding(
                horizontal = MasroofSpacing.rowHorizontalPadding,
                vertical = MasroofSpacing.rowVerticalPadding,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
