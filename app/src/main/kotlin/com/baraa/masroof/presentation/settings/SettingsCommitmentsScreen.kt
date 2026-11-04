
package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.settings.CommitmentRecordEvent
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
fun SettingsCommitmentsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenCommitment: (ManagedCommitmentUi) -> Unit,
    onOpenCommitmentById: (String) -> Unit = {},
    onListTabChange: (CommitmentsListTab) -> Unit = {},
    onToggle: (ManagedCommitmentUi) -> Unit = {},
    onDelete: (ManagedCommitmentUi) -> Unit = {},
) {
    var deleteTargetId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    (state.activeCommitments + state.disabledCommitments).find { it.id == deleteTargetId }?.let { target ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.settings_commitment_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_commitment_delete_confirm_body)) },
            confirmButton = { com.baraa.masroof.presentation.common.MasroofTextButton({ deleteTargetId = null; onDelete(target) }) { Text(stringResource(R.string.reference_delete)) } },
            dismissButton = { com.baraa.masroof.presentation.common.MasroofTextButton({ deleteTargetId = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
    MasroofSecondaryScaffold(
        title = stringResource(R.string.reference_manage_commitments),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap), verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofReference.shortcutGap)) {
                listOf(CommitmentsListTab.ACTIVE to R.string.settings_commitments_tab_active,
                    CommitmentsListTab.DISABLED to R.string.settings_commitments_tab_disabled,
                    CommitmentsListTab.RECORD to R.string.settings_commitments_tab_record).forEach { (tab, label) ->
                    com.baraa.masroof.presentation.common.MasroofChoice(stringResource(label), state.commitmentsListTab == tab, { onListTabChange(tab) })
                }
            }
            when (state.commitmentsListTab) {
                CommitmentsListTab.ACTIVE,
                CommitmentsListTab.DISABLED,
                -> {
                    val commitments = when (state.commitmentsListTab) {
                        CommitmentsListTab.ACTIVE -> state.activeCommitments
                        CommitmentsListTab.DISABLED -> state.disabledCommitments
                        CommitmentsListTab.RECORD -> emptyList()
                    }
                    if (commitments.isEmpty()) {
                        Text(
                            when (state.commitmentsListTab) {
                                CommitmentsListTab.ACTIVE -> stringResource(R.string.settings_commitments_active_empty)
                                CommitmentsListTab.DISABLED -> stringResource(R.string.settings_commitments_disabled_empty)
                                CommitmentsListTab.RECORD -> stringResource(R.string.settings_commitments_record_empty)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MasroofSpacing.screenHorizontal),
                        )
                        return@Column
                    }
                    commitments.forEach { commitment ->
                        com.baraa.masroof.presentation.common.MasroofCard {
                            Text(commitment.name, style = MaterialTheme.typography.titleMedium)
                            com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.settings_commitment_amount), formatLocalizedMoney(commitment.amount))
                            com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.settings_commitment_recurrence),
                                commitment.recurrence?.let { recurrenceLabel(it) } ?: stringResource(R.string.settings_commitment_recurrence_none))
                            com.baraa.masroof.presentation.common.MasroofDetailRow(stringResource(R.string.settings_commitment_transaction_date),
                                formatLocalizedTransactionDate(commitment.transactionDate))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                                com.baraa.masroof.presentation.common.MasroofManagementAction(stringResource(R.string.reference_edit), { onOpenCommitment(commitment) })
                                com.baraa.masroof.presentation.common.MasroofManagementAction(stringResource(if (commitment.active) R.string.reference_pause else R.string.settings_commitment_enable), { onToggle(commitment) }, enabled = !state.savingCommitment)
                                com.baraa.masroof.presentation.common.MasroofManagementAction(stringResource(R.string.reference_delete), { deleteTargetId = commitment.id }, enabled = !state.savingCommitment, danger = true)
                            }
                        }

                    }
                }
                CommitmentsListTab.RECORD -> {
                    if (state.commitmentRecordEntries.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_commitments_record_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MasroofSpacing.screenHorizontal),
                        )
                        return@Column
                    }
                    state.commitmentRecordEntries.forEach { entry ->
                        SettingsNavRow(
                            icon = recordIcon(entry.event),
                            title = entry.commitmentName,
                            subtitle = recordSubtitle(entry),
                            onClick = { onOpenCommitmentById(entry.commitmentId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun recordIcon(event: CommitmentRecordEvent) = when (event) {
    CommitmentRecordEvent.CREATED -> MasroofIcons.calendar
    CommitmentRecordEvent.PAUSED -> MasroofIcons.warning
    CommitmentRecordEvent.RESUMED -> MasroofIcons.externalIn
}

@Composable
private fun recordSubtitle(entry: CommitmentRecordEntryUi): String {
    val dateLabel = formatLocalizedTransactionDate(entry.at)
    val eventLabel = when (entry.event) {
        CommitmentRecordEvent.CREATED -> stringResource(R.string.settings_commitment_record_created)
        CommitmentRecordEvent.PAUSED -> stringResource(R.string.settings_commitment_record_paused)
        CommitmentRecordEvent.RESUMED -> stringResource(R.string.settings_commitment_record_resumed)
    }
    return stringResource(R.string.settings_commitment_record_line, eventLabel, dateLabel)
}

@Composable
private fun recurrenceLabel(value: com.baraa.masroof.domain.model.CommitmentRecurrence): String = stringResource(when (value) {
    com.baraa.masroof.domain.model.CommitmentRecurrence.WEEKLY -> R.string.settings_commitment_recurrence_weekly
    com.baraa.masroof.domain.model.CommitmentRecurrence.MONTHLY -> R.string.settings_commitment_recurrence_monthly
    com.baraa.masroof.domain.model.CommitmentRecurrence.YEARLY -> R.string.settings_commitment_recurrence_yearly
})
