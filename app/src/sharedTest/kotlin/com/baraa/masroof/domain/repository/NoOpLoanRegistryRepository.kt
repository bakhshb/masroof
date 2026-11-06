package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus

object NoOpLoanRegistryRepository : LoanRegistryRepository {
    override suspend fun observe(reference: LoanReference, rawSmsId: String) = Unit

    override suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus) = Unit

    override suspend fun resolve(reference: LoanReference): OwnershipStatus = OwnershipStatus.UNKNOWN

    override suspend fun get(reference: LoanReference): LoanRegistryEntry? = null

    override suspend fun listAll(): List<LoanRegistryEntry> = emptyList()

    override suspend fun updateDisplayName(reference: LoanReference, displayName: String?) = Unit
}
