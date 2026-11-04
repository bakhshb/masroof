package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.repository.CommitmentRepository

open class NoOpCommitmentRepository(
    private val commitments: List<Commitment> = emptyList(),
) : CommitmentRepository {
    override suspend fun create(commitment: Commitment) = Unit

    override suspend fun update(commitment: Commitment) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun get(id: String): Commitment? = commitments.find { it.id == id }

    override suspend fun getBySourceTransactionId(sourceTransactionId: String): Commitment? =
        commitments.find { it.sourceTransactionId == sourceTransactionId }

    override suspend fun listAll(): List<Commitment> = commitments

}
