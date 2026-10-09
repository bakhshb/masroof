package com.baraa.masroof.application.commitment

import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.CommitmentRepository.CommitmentDraft
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import java.time.Clock
import java.time.ZoneId

sealed interface CommitmentCreationResult {
    data object Success : CommitmentCreationResult

    data object AlreadyExists : CommitmentCreationResult

    data class Rejected(
        val reason: String,
    ) : CommitmentCreationResult
}

class CommitmentFromTransactionService(
    private val commitmentRepository: CommitmentRepository,
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun createFromTransaction(transactionId: String): CommitmentCreationResult {
        if (commitmentRepository.getBySourceTransactionId(transactionId) != null) {
            return CommitmentCreationResult.AlreadyExists
        }
        val transaction = financialTransactionRepository.getById(transactionId)
            ?: return CommitmentCreationResult.Rejected("transaction_not_found")

        val draft = buildDraft(transaction) ?: return CommitmentCreationResult.Rejected("invalid_transaction")
        val now = clock.instant()
        try {
            commitmentRepository.create(
                Commitment(
                    id = RegistryEntityIdFactory.newCommitmentId(),
                    name = draft.name,
                    amount = draft.amount,
                    transactionDate = draft.transactionDate,
                    recurrence = draft.recurrence,
                    dueDate = draft.dueDate,
                    active = true,
                    sourceTransactionId = draft.sourceTransactionId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (_: Exception) {
            return if (commitmentRepository.getBySourceTransactionId(transactionId) != null) {
                CommitmentCreationResult.AlreadyExists
            } else {
                CommitmentCreationResult.Rejected("create_failed")
            }
        }
        return CommitmentCreationResult.Success
    }

    companion object {
        fun canMarkAsCommitment(type: FinancialTransactionType): Boolean =
            type == FinancialTransactionType.EXPENSE ||
                type == FinancialTransactionType.FEE ||
                type == FinancialTransactionType.BILL_PAYMENT
    }

    internal fun buildDraft(transaction: FinancialTransaction): CommitmentDraft? {
        if (!canMarkAsCommitment(transaction.type)) {
            return null
        }
        val name = transaction.merchant?.trim()?.takeIf { it.isNotEmpty() }
            ?: transaction.counterparty?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (transaction.amount.amount.signum() <= 0) return null
        val transactionDate = transaction.occurredAt.atZone(zoneId).toLocalDate()
        return CommitmentDraft(
            name = name,
            amount = transaction.amount,
            transactionDate = transactionDate,
            recurrence = CommitmentRecurrence.MONTHLY,
            sourceTransactionId = transaction.id,
        )
    }
}
