package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

@Composable
internal fun ApprovedOwnershipCandidateCard(
    bankLabel: String, suffixLabel: String, isAccount: Boolean,
    ownedLabel: String, externalLabel: String, status: String, statusIcon: androidx.compose.ui.graphics.vector.ImageVector,
    statusColor: androidx.compose.ui.graphics.Color,
    onOwned: () -> Unit, onExternal: () -> Unit, enabled: Boolean, canConfirm: Boolean,
    modifier: Modifier = Modifier, ownedTag: String, externalTag: String,
    collisionContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    footer: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    MasroofCard(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isAccount) {
                        MasroofOutlineIcons.ArrowDownLeft
                    } else {
                        MasroofOutlineIcons.CreditCard
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MasroofSizes.size22),
                )
                Spacer(Modifier.size(MasroofSpacing.sectionHeaderGap))
                ApprovedText(
                    bankLabel,
                )
            }
            ApprovedText(suffixLabel)
            collisionContent()
            Row(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                ApprovedIconTextButton(onClick = onOwned, icon = MasroofOutlineIcons.CircleCheck, text = ownedLabel, enabled = enabled && canConfirm, modifier=Modifier.testTag(ownedTag))
                ApprovedIconTextButton(onClick = onExternal, icon = MasroofOutlineIcons.TriangleAlert, text = externalLabel, enabled = enabled, modifier=Modifier.testTag(externalTag))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(MasroofIconSizes.sm),
                    tint = statusColor,
                )
                Spacer(Modifier.size(MasroofSpacing.smallGap))
                ApprovedText(
                    status,
                )
            }
            footer()
        }
    }
}
