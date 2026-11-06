package com.baraa.masroof.presentation.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.baraa.masroof.R

internal typealias MasroofGuidancePositionProvider = com.baraa.masroof.ui.components.MasroofGuidancePositionProvider

/** Contextual help renderer; app owns translated display strings. */
@Composable
fun MasroofGuidanceButton(title: String, message: String, modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    com.baraa.masroof.ui.components.MasroofGuidanceButton(
        com.baraa.masroof.application.locale.AppDigits.display(title),
        com.baraa.masroof.application.locale.AppDigits.display(message), modifier, contentColor,
        com.baraa.masroof.application.locale.AppDigits.display(stringResource(R.string.guidance_about, title)))

@Composable
fun MasroofGuidanceLabel(
    label: String,
    message: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MasroofText(label, Modifier.weight(1f), style = style, color = contentColor)
        message?.takeIf { it.isNotBlank() }?.let {
            MasroofGuidanceButton(label, it, contentColor = contentColor)
        }
    }
}
