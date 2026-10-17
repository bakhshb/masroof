package com.baraa.masroof.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.baraa.masroof.ui.theme.MasroofReference

/** Typography only; no monetary parsing or arithmetic happens in the UI. */
@Composable
fun MasroofReferenceMoney(value: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    val display = value
    val amount = Regex("[+-]?[0-9][0-9,.]*").find(display)
    val annotated = buildAnnotatedString {
        if (amount == null) append(display) else {
            val unit = SpanStyle(fontSize = MasroofReference.unit.fontSize,
                fontWeight = MasroofReference.unit.fontWeight)
            withStyle(unit) { append(display.substring(0, amount.range.first)) }
            append(amount.value)
            withStyle(unit) { append(display.substring(amount.range.last + 1)) }
        }
    }
    androidx.compose.material3.Text(annotated, modifier, style = style, color = color)
}
