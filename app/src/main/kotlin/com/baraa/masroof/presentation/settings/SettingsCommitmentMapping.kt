package com.baraa.masroof.presentation.settings

import com.baraa.masroof.application.settings.CommitmentHistoryBuilder
import com.baraa.masroof.application.settings.CommitmentRecordBuilder
import com.baraa.masroof.domain.model.Commitment

internal fun SettingsUiState.withCommitments(commitments: List<Commitment>): SettingsUiState {
    val zoneId = java.time.ZoneId.systemDefault()
    val items = commitments.map { commitment -> toManagedCommitmentUi(commitment, zoneId) }
    val disabledItems = CommitmentHistoryBuilder.disabledTabItems(commitments, zoneId)
        .map { historyItem ->
            items.first { it.id == historyItem.commitmentId }
        }
    val recordEntries = CommitmentRecordBuilder.recordTabItems(commitments)
        .map { entry ->
            CommitmentRecordEntryUi(
                commitmentId = entry.commitmentId,
                commitmentName = entry.commitmentName,
                event = entry.event,
                at = entry.at.atZone(zoneId).toLocalDate(),
            )
        }
    return copy(
        activeCommitments = items.filter { item -> item.active },
        disabledCommitments = disabledItems,
        commitmentRecordEntries = recordEntries,
        commitmentsLoaded = true,
    )
}

private fun toManagedCommitmentUi(
    commitment: Commitment,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): ManagedCommitmentUi =
    ManagedCommitmentUi(
        id = commitment.id,
        name = commitment.name,
        amount = commitment.amount,
        transactionDate = commitment.transactionDate,
        recurrence = commitment.recurrence,
        dueDate = commitment.dueDate,
        active = commitment.active,
        sourceTransactionId = commitment.sourceTransactionId,
        pauseIntervals = CommitmentHistoryBuilder.intervalSummaries(commitment, zoneId),
    )
