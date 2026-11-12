package com.baraa.masroof.domain.model

/**
 * Loan container discovered or confirmed for a [Bank].
 */
data class LoanRegistryEntry(
    val id: String,
    val bank: Bank,
    val loanType: LoanType,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
)
