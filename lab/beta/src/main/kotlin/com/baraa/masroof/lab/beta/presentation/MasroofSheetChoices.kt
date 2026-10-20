package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as MasroofText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.ui.theme.*

@Composable
internal fun <T> MasroofSheetChoices(options: List<T>, selected: Set<T>, label: @Composable (T) -> String,
    onToggle: (T) -> Unit, optionKey: (T) -> String, enabled: Boolean = true) {
    BoxWithConstraints {
        val columns = if (maxWidth >= MasroofSheetTokens.twoColumnContentWidth) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
            options.chunked(columns).forEach { choices ->
                Row(horizontalArrangement = Arrangement.spacedBy(MasroofSheetTokens.sectionGap)) {
                    choices.forEach { value ->
                        Row(Modifier.weight(1f).heightIn(min = MasroofSheetTokens.choiceMinHeight)
                            .toggleable(value in selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(value) })
                            .testTag(optionKey(value)), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                            Checkbox(value in selected, onCheckedChange = null, enabled = enabled,
                                colors = CheckboxDefaults.colors(checkedColor = MasroofPalette.Primary,
                                    checkmarkColor = MasroofLightColors.OnPrimary))
                            MasroofText(label(value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                    if (choices.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
