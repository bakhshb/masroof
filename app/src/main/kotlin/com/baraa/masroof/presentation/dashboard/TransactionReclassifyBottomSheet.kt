package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.baraa.masroof.R
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.MasroofIcons

private val RECLASSIFY_TYPE_OPTIONS: List<FinancialTransactionType> =
    TransactionReclassificationService.ALLOWED_TYPES.toList().sortedBy { it.name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionReclassifyBottomSheet(
    currentType: FinancialTransactionType,
    selectedType: FinancialTransactionType?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSelectType: (FinancialTransactionType) -> Unit,
    onSave: () -> Unit,
) {
    val canSave = selectedType != null && selectedType != currentType && !saving
    com.baraa.masroof.presentation.common.MasroofBottomSheet(
        title = stringResource(R.string.transaction_reclassify_sheet_title), onDismiss = onDismiss,
        onApply = onSave, applyLabel = stringResource(R.string.transaction_reclassify_sheet_save),
        canApply = canSave, busy = saving) {
            selectedType?.takeIf { it != currentType }?.let { pending ->
                Text(
                    stringResource(
                        R.string.transaction_reclassify_preview,
                        transactionTypeLabel(currentType),
                        transactionTypeLabel(pending),
                    ),
                    modifier = Modifier.padding(
                        horizontal = MasroofSpacing.screenHorizontal,
                        vertical = MasroofSpacing.sectionHeaderGap,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    ,
            ) {
                RECLASSIFY_TYPE_OPTIONS.forEach { type ->
                    val isCurrent = type == currentType
                    val isSelected = type == (selectedType ?: currentType)
                    // One selectable radio row: TalkBack reads one target with its role and state.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                enabled = !isCurrent && !saving,
                                role = Role.RadioButton,
                                onClick = { onSelectType(type) },
                            )
                            .padding(
                        horizontal = MasroofSpacing.sectionHeaderGap,
                        vertical = MasroofSpacing.inlineGap,
                    ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            enabled = !isCurrent && !saving,
                        )
                        Icon(
                            imageVector = MasroofIcons.transactionType(type),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(MasroofIconSizes.lg),
                        )
                        Spacer(Modifier.width(MasroofSpacing.carouselGap))
                        Text(
                            transactionTypeLabel(type),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(
                                stringResource(R.string.transaction_reclassify_current_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

    }
}
