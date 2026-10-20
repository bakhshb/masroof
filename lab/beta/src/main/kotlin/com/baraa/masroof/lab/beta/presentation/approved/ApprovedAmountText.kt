package com.baraa.masroof.lab.beta.presentation.approved

import com.baraa.masroof.ui.theme.MasroofFontWeights
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

internal enum class ApprovedAmountRole {
    Hero,
    Card,
    Tile,
    List,
}

internal object ApprovedTextStyles {
    val heroAmount: TextStyle
        @Composable get() = MaterialTheme.typography.displayMedium

    val cardAmount: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(fontWeight = MasroofFontWeights.bold)

    val tileAmount: TextStyle
        @Composable get() = MaterialTheme.typography.titleSmall.copy(fontWeight = MasroofFontWeights.bold)

    val listAmount: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge.copy(fontWeight = MasroofFontWeights.bold)


}

@Composable
internal fun ApprovedAmountText(
    amount: String,
    role: ApprovedAmountRole,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    heroScale: Float = 1f,
) {
    val baseStyle = when (role) {
        ApprovedAmountRole.Hero -> ApprovedTextStyles.heroAmount.copy(
            fontSize = ApprovedTextStyles.heroAmount.fontSize * heroScale,
        )
        ApprovedAmountRole.Card -> ApprovedTextStyles.cardAmount
        ApprovedAmountRole.Tile -> ApprovedTextStyles.tileAmount
        ApprovedAmountRole.List -> ApprovedTextStyles.listAmount
    }
    val resolvedColor = if (color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        color
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
