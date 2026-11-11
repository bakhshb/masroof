package com.baraa.masroof.domain.ownership

import com.baraa.masroof.domain.model.Bank

/**
 * Shared registry identity rules for accounts and cards.
 *
 * [Bank.UNKNOWN] is not a durable identity and must never be persisted or
 * confirmed in the ownership registries (cross-bank linking is reconciliation's job).
 */
object RegistryIdentity {
    fun requireKnownBank(bank: Bank, what: String) {
        require(bank != Bank.UNKNOWN) {
            "$what rejects Bank.UNKNOWN — not a durable financial identity"
        }
    }

    fun isKnownBank(bank: Bank): Boolean = bank != Bank.UNKNOWN
}
