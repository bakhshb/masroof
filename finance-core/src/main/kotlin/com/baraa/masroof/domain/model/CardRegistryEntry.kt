package com.baraa.masroof.domain.model

/**
 * Durable card-container knowledge in the ownership registry.
 *
 * Canonical identity: [id]. [bank] + [last4] are bank-scoped identifiers.
 */
data class CardRegistryEntry(
    val id: String,
    val bank: Bank,
    val last4: String,
    val ownership: OwnershipStatus,
    val displayName: String? = null,
    val cardNetwork: CardNetwork? = null,
    val cardType: CardType? = null,
    val linkedAccountBankId: String? = null,
    val linkedAccountMaskedNumber: String? = null,
    val parentCardLast4: String? = null,
    val cardRole: CardRole? = null,
    val creditFacilityId: String? = null,
    val firstSeenRawSmsId: String?,
    val lastSeenRawSmsId: String?,
) {
    companion object {
        fun forTest(
            bank: Bank,
            last4: String,
            ownership: OwnershipStatus = OwnershipStatus.OWNED,
            displayName: String? = null,
            cardNetwork: CardNetwork? = null,
            cardType: CardType? = null,
            linkedAccountBankId: String? = null,
            linkedAccountMaskedNumber: String? = null,
            parentCardLast4: String? = null,
            cardRole: CardRole? = null,
            creditFacilityId: String? = null,
            firstSeenRawSmsId: String? = "sms",
            lastSeenRawSmsId: String? = "sms",
            id: String = com.baraa.masroof.domain.ids.RegistryEntityIdFactory.stableCardId(
                bank.id,
                last4,
            ),
        ): CardRegistryEntry =
            CardRegistryEntry(
                id = id,
                bank = bank,
                last4 = last4,
                ownership = ownership,
                displayName = displayName,
                cardNetwork = cardNetwork,
                cardType = cardType,
                linkedAccountBankId = linkedAccountBankId,
                linkedAccountMaskedNumber = linkedAccountMaskedNumber,
                parentCardLast4 = parentCardLast4,
                cardRole = cardRole,
                creditFacilityId = creditFacilityId,
                firstSeenRawSmsId = firstSeenRawSmsId,
                lastSeenRawSmsId = lastSeenRawSmsId,
            )
    }

    val linkedAccount: AccountReference?
        get() {
            val bankId = linkedAccountBankId ?: return null
            val masked = linkedAccountMaskedNumber ?: return null
            return AccountReference(Bank(bankId), masked)
        }
}
