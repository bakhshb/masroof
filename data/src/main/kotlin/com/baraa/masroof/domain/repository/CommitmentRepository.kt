package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.core.money.Money
import java.time.LocalDate

interface CommitmentRepository {
    suspend fun create(commitment: Commitment)

    suspend fun update(commitment: Commitment)

    suspend fun delete(id: String)

    suspend fun get(id: String): Commitment?

    suspend fun getBySourceTransactionId(sourceTransactionId: String): Commitment?

    suspend fun listAll(): List<Commitment>

    data class CommitmentDraft(
        val name: String,
        val amount: Money,
        val transactionDate: LocalDate,
        val recurrence: CommitmentRecurrence? = null,
        val dueDate: LocalDate? = null,
        val sourceTransactionId: String,
    )
}
