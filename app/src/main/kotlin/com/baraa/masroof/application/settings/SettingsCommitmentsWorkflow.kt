package com.baraa.masroof.application.settings

import com.baraa.masroof.application.commitment.CommitmentPauseTransitions
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.sms.time.InstantClock
import java.time.LocalDate

/**
 * Settings-screen commitment reads and edits.
 */
class SettingsCommitmentsWorkflow(
    private val commitmentRepository: CommitmentRepository,
    private val clock: InstantClock = InstantClock.System,
) {
    data class CommitmentEditorDraft(
        val name: String,
        val amount: Money,
        val transactionDate: LocalDate,
        val recurrence: CommitmentRecurrence?,
        val dueDate: LocalDate?,
    )

    suspend fun listAll(): List<Commitment> = commitmentRepository.listAll()

    suspend fun get(commitmentId: String): Commitment? = commitmentRepository.get(commitmentId)

    suspend fun update(commitmentId: String, draft: CommitmentEditorDraft) {
        val existing = commitmentRepository.get(commitmentId) ?: return
        if (draft.amount.amount.signum() <= 0) return
        val now = clock.now()
        commitmentRepository.update(
            existing.copy(
                name = draft.name,
                amount = draft.amount,
                transactionDate = draft.transactionDate,
                recurrence = draft.recurrence,
                dueDate = draft.dueDate,
                updatedAt = now,
            ),
        )
    }

    suspend fun toggleActive(commitmentId: String) {
        val existing = commitmentRepository.get(commitmentId) ?: return
        val now = clock.now()
        commitmentRepository.update(CommitmentPauseTransitions.toggle(existing, now))
    }

    suspend fun delete(commitmentId: String) {
        commitmentRepository.delete(commitmentId)
    }
}
