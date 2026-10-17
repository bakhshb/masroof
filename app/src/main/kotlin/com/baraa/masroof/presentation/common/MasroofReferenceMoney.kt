package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.baraa.masroof.ui.theme.MasroofReference

@Composable
fun MasroofReferenceMoney(value: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = MaterialTheme.colorScheme.onSurface) =
    com.baraa.masroof.ui.components.MasroofReferenceMoney(
        com.baraa.masroof.application.locale.AppDigits.display(value), modifier, style, color)
