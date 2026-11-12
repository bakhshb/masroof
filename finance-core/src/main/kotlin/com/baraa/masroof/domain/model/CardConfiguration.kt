package com.baraa.masroof.domain.model

/** An explicitly confirmed card setup, independent of ownership and transaction history. */
data class CardConfiguration(
    val network: CardNetwork?,
    val type: CardType,
    val role: CardRole? = null,
    val primaryLast4: String? = null,
    val linkedAccount: AccountReference? = null,
) {
    fun validate(bank: Bank, last4: String) {
        require(network != CardNetwork.MADA || type == CardType.DEBIT) { "mada_requires_debit" }
        if (type == CardType.CREDIT) {
            require(role != null && linkedAccount == null) { "credit_requires_role_without_account" }
            require((role == CardRole.SUPPLEMENTARY) == (primaryLast4 != null)) { "invalid_card_parent" }
            require(primaryLast4 == null || (primaryLast4.isNotBlank() && primaryLast4 != last4)) { "invalid_card_parent" }
        } else {
            require(role == null && primaryLast4 == null) { "debit_cannot_have_credit_role" }
            linkedAccount?.let {
                require(it.bank == bank && !it.maskedNumber.isNullOrBlank()) { "invalid_linked_account" }
            }
        }
    }
}
