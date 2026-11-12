package com.baraa.masroof.domain.model

/**
 * Durable account-container knowledge in the ownership registry.
 *
 * Canonical identity: [id]. [bank] + [maskedNumber] are bank-scoped identifiers.
 */
data class AccountRegistryEntry(
    val id: String,
    val bank: Bank,
    val maskedNumber: String,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
    val accountType: AccountType = AccountType.CURRENT,
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
) {
    companion object {
        fun forTest(
            bank: Bank,
            maskedNumber: String,
            ownership: OwnershipStatus = OwnershipStatus.OWNED,
            displayName: String? = null,
            accountType: AccountType = AccountType.CURRENT,
            firstSeenRawSmsId: String? = "sms",
            lastSeenRawSmsId: String? = "sms",
            id: String = com.baraa.masroof.domain.ids.RegistryEntityIdFactory.stableAccountId(
                bank.id,
                maskedNumber,
            ),
        ): AccountRegistryEntry =
            AccountRegistryEntry(
                id = id,
                bank = bank,
                maskedNumber = maskedNumber,
                ownership = ownership,
                displayName = displayName,
                accountType = accountType,
                firstSeenRawSmsId = firstSeenRawSmsId,
                lastSeenRawSmsId = lastSeenRawSmsId,
            )
    }
}
