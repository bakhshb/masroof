package com.baraa.masroof.presentation.common

import com.baraa.masroof.ui.theme.MasroofFontWeights
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

enum class MasroofAmountRole {
    Hero,
    Card,
    Tile,
    List,
}

object MasroofTextStyles {
    val heroAmount: TextStyle
        @Composable get() = MaterialTheme.typography.displayMedium

    val cardAmount: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(fontWeight = MasroofFontWeights.bold)

    val tileAmount: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(fontWeight = MasroofFontWeights.bold)

    val listAmount: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = MasroofFontWeights.bold)

    val sectionTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(fontWeight = MasroofFontWeights.bold)

    val cardTitle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = MasroofFontWeights.semiBold)

    val hint: TextStyle
        @Composable get() = MaterialTheme.typography.bodySmall

    val screenLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge

    val breakdownLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelMedium
}

@Composable
fun MasroofAmountText(
    amount: String,
    role: MasroofAmountRole,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    heroScale: Float = 1f,
) {
    val baseStyle = when (role) {
        MasroofAmountRole.Hero -> MasroofTextStyles.heroAmount.copy(
            fontSize = MasroofTextStyles.heroAmount.fontSize * heroScale,
        )
        MasroofAmountRole.Card -> MasroofTextStyles.cardAmount
        MasroofAmountRole.Tile -> MasroofTextStyles.tileAmount
        MasroofAmountRole.List -> MasroofTextStyles.listAmount
    }
    val resolvedColor = if (color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        color
    }
    if (maxLines == 1) {
        com.baraa.masroof.ui.components.MasroofFitText(com.baraa.masroof.application.locale.AppDigits.display(amount),
            baseStyle.copy(color = resolvedColor), modifier, textAlign = textAlign)
        return
    }
    Text(
        amount,
        modifier = modifier,
        style = baseStyle.copy(color = resolvedColor),
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
