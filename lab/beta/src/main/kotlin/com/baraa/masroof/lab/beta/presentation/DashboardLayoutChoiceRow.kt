package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.baraa.masroof.ui.theme.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown

@Composable
internal fun DashboardLayoutChoiceRow(title: String, icon: ImageVector, visible: Boolean,
    canMoveUp: Boolean, canMoveDown: Boolean, upDescription: String, downDescription: String,
    onToggle: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier, extraContent: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = MasroofSpacing.none)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = MasroofFontWeights.semiBold))
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, upDescription) }
                IconButton(onClick = onMoveDown, enabled = enabled && canMoveDown) { Icon(Icons.Default.KeyboardArrowDown, downDescription) }
            }
            Switch(visible, onCheckedChange = { onToggle() }, enabled = enabled)
        }
        extraContent?.invoke()
    }
}
