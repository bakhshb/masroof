package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.CommitmentRepository

class DashboardCommitmentsWorkflow(
    private val commitmentFromTransactionService: CommitmentFromTransactionService,
    private val commitmentRepository: CommitmentRepository,
) {
    suspend fun createFromTransaction(transactionId: String, fallbackName: String? = null): CommitmentCreationResult =
        commitmentFromTransactionService.createFromTransaction(transactionId, fallbackName)

    suspend fun committedSourceTransactionIds(): Set<String> =
        commitmentRepository.listAll().map { it.sourceTransactionId }.toSet()

    fun canMarkAsCommitment(type: FinancialTransactionType): Boolean =
        CommitmentFromTransactionService.canMarkAsCommitment(type)
}
