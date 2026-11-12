package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.OwnershipStatus

/**
 * Durable account ownership registry. Discovery observes; confirmation sets status.
 */
interface AccountRegistryRepository {
    /**
     * Records that [reference] was seen in [rawSmsId].
     *
     * New rows start as [OwnershipStatus.UNKNOWN]. Existing OWNED/EXTERNAL
     * statuses are preserved. Same [rawSmsId] as [AccountRegistryEntry.lastSeenRawSmsId]
     * is a no-op (idempotent).
     */
    suspend fun observe(reference: AccountReference, rawSmsId: String)

    suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus)

    suspend fun resolve(reference: AccountReference): OwnershipStatus

    suspend fun get(reference: AccountReference): AccountRegistryEntry?

    suspend fun listAll(): List<AccountRegistryEntry>

    suspend fun updateDisplayName(reference: AccountReference, displayName: String?)

    suspend fun updateAccountType(reference: AccountReference, accountType: AccountType)

    /** Removes the registry row only; callers verify nothing still references the account. */
    suspend fun delete(reference: AccountReference): Unit =
        throw UnsupportedOperationException("Account registry delete is not supported")
}
