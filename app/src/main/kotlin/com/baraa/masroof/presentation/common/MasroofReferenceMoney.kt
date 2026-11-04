package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Composable
fun MasroofReferenceMoney(value: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = MaterialTheme.colorScheme.onSurface) =
    com.baraa.masroof.ui.components.MasroofReferenceMoney(
        com.baraa.masroof.application.locale.AppDigits.display(value), modifier, style, color)
