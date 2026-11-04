
package com.baraa.masroof.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun OwnershipInlinePrompt(
    titleRes: Int,
    confirmRes: Int,
    enabled: Boolean,
    onConfirmOwned: () -> Unit,
    onMarkExternal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MasroofIcons.warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(MasroofIconSizes.sm),
            )
            Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
            IconTextButton(
                onClick = onConfirmOwned,
                enabled = enabled,
                icon = MasroofIcons.success,
                text = stringResource(confirmRes),
            )
            IconTextButton(
                onClick = onMarkExternal,
                enabled = enabled,
                icon = MasroofIcons.warning,
                text = stringResource(R.string.ownership_action_not_mine),
            )
        }
    }
}
