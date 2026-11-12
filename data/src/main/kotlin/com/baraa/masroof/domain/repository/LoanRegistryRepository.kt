package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus

interface LoanRegistryRepository {
    suspend fun observe(reference: LoanReference, rawSmsId: String)

    suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus)

    suspend fun resolve(reference: LoanReference): OwnershipStatus

    suspend fun get(reference: LoanReference): LoanRegistryEntry?

    suspend fun listAll(): List<LoanRegistryEntry>

    suspend fun updateDisplayName(reference: LoanReference, displayName: String?)

    /** Removes the registry row only; callers verify nothing still references the loan. */
    suspend fun delete(reference: LoanReference): Unit =
        throw UnsupportedOperationException("Loan registry delete is not supported")
}
