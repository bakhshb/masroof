package com.baraa.masroof.domain.ownership

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository

/**
 * Role-aware discovery of user-container *candidates* from [ParsedEvent]s.
 *
 * Discovery is not ownership: candidates start as UNKNOWN until confirmation.
 * [com.baraa.masroof.domain.model.BankNetworkType] is never consulted.
 * [Bank.UNKNOWN] references are never observed (no cross-bank guessing).
 *
 * Historical backlog: callers iterate persisted events and invoke [observe]
 * (see application-layer wiring) — this type stays free of parsing imports.
 */
class OwnershipDiscoveryService(
    private val accountRegistry: AccountRegistryRepository,
    private val cardRegistry: CardRegistryRepository,
    private val loanRegistry: LoanRegistryRepository,
) {
    /**
     * Observe user-side container candidates from one parsed event.
     */
    suspend fun observe(event: ParsedEvent, loanType: LoanType? = null) {
        val accounts = mutableListOf<AccountReference>()
        val cards = mutableListOf<CardReference>()
        val loans = mutableListOf<LoanReference>()

        when (event.messageFamily) {
            MessageFamily.TRANSFER_OUT -> {
                event.sourceAccountRef?.let(accounts::add)
                // destination is not automatically the user's account
            }

            MessageFamily.TRANSFER_IN -> {
                event.destinationAccountRef?.let(accounts::add)
                // source is not automatically the user's account
            }

            MessageFamily.PURCHASE,
            MessageFamily.CARD_PAYMENT,
            MessageFamily.WITHDRAWAL,
            MessageFamily.FEE,
            -> {
                event.sourceAccountRef?.let(accounts::add)
                event.cardRef?.let(cards::add)
            }

            MessageFamily.BILL_PAYMENT -> {
                event.sourceAccountRef?.let(accounts::add)
            }

            MessageFamily.FINANCING_INSTALLMENT -> {
                event.sourceAccountRef?.let(accounts::add)
                loanType?.let { loans += LoanReference(event.bank, it) }
            }

            MessageFamily.BALANCE_NOTICE -> {
                event.sourceAccountRef?.let(accounts::add)
                    ?: event.destinationAccountRef?.let(accounts::add)
            }

            MessageFamily.REFUND -> {
                // Only clearly identified user destination / card — never a
                // generic source fallback when destination is absent.
                event.destinationAccountRef?.let(accounts::add)
                event.cardRef?.let(cards::add)
            }

            MessageFamily.OTP,
            MessageFamily.NON_FINANCIAL,
            MessageFamily.UNKNOWN,
            -> Unit
        }

        for (ref in accounts) {
            if (isDiscoverableAccount(ref)) {
                accountRegistry.observe(ref, event.rawSmsId)
            }
        }
        for (ref in cards) {
            if (isDiscoverableCard(ref)) {
                cardRegistry.observe(ref, event.rawSmsId)
            }
        }
        for (ref in loans) {
            if (isDiscoverableLoan(ref)) {
                loanRegistry.observe(ref, event.rawSmsId)
            }
        }
    }

    private fun isDiscoverableLoan(ref: LoanReference): Boolean = ref.bank != Bank.UNKNOWN

    private fun isDiscoverableAccount(ref: AccountReference): Boolean {
        val masked = ref.maskedNumber?.trim().orEmpty()
        return ref.bank != Bank.UNKNOWN && masked.isNotEmpty()
    }

    private fun isDiscoverableCard(ref: CardReference): Boolean {
        val last4 = ref.last4?.trim().orEmpty()
        return ref.bank != Bank.UNKNOWN && last4.isNotEmpty()
    }
}
