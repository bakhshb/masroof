package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType

/**
 * Which stored transactions belong to one account, card or loan detail screen.
 *
 * Container ids are financial identity, so they are built and read here, never in presentation.
 */
object EntityTransactionScope {
    /** One owned account's container identity; presentation passes it back without reading it. */
    class Account internal constructor(internal val containerId: String, internal val last4s: Set<String>)

    fun account(bank: Bank, maskedNumber: String): Account = Account(
        FinancialContainerIdFactory.accountId(bank, maskedNumber),
        CurrentAccountTransactionScope.ownedAccountLast4sFromMaskedNumbers(listOf(maskedNumber)),
    )

    /** Account whose stored id is [ownedContainerId], matching another container by the same last four digits. */
    fun account(ownedContainerId: String, ownedLast4s: Set<String>): Account = Account(ownedContainerId, ownedLast4s)

    fun matchesAccount(containerId: String?, account: Account): Boolean {
        if (containerId == null) return false
        if (containerId == account.containerId) return true
        if (!containerId.startsWith("account:")) return false
        return containerId.substringAfterLast(':') in account.last4s
    }

    fun involvesAccount(account: Account, sourceContainerId: String?, destinationContainerId: String?, involved: Set<String>): Boolean =
        matchesAccount(sourceContainerId, account) || matchesAccount(destinationContainerId, account) ||
            involved.any { matchesAccount(it, account) }

    fun involvesLoan(bank: Bank, loanType: LoanType, type: FinancialTransactionType, destinationContainerId: String?,
        involved: Set<String>): Boolean {
        val loanContainerId = FinancialContainerIdFactory.loanId(bank, loanType)
        return loanContainerId in involved ||
            (type == FinancialTransactionType.LOAN_REPAYMENT && destinationContainerId == loanContainerId)
    }

    fun involvesDebitCard(bank: Bank, last4: String, debitSpendInvolved: Set<String>): Boolean =
        CardTransactionInvolvementResolver.cardKey(bank.id, last4) in debitSpendInvolved

    fun involvesCard(bank: Bank, last4: String, sourceContainerId: String?, destinationContainerId: String?,
        cardLast4: String?, involved: Set<String>): Boolean {
        if (CardTransactionInvolvementResolver.cardKey(bank.id, last4) in involved) return true
        val cardContainerId = FinancialContainerIdFactory.cardId(bank, last4)
        if (sourceContainerId == cardContainerId || destinationContainerId == cardContainerId) return true
        if (cardLast4 != last4) return false
        val sourceBankId = FinancialContainerIdParser.cardBankId(sourceContainerId)
        val destinationBankId = FinancialContainerIdParser.cardBankId(destinationContainerId)
        if (sourceBankId != null && sourceBankId != bank.id) return false
        if (destinationBankId != null && destinationBankId != bank.id) return false
        val bankPrefix = "card:${bank.id}:"
        return when {
            sourceContainerId?.startsWith(bankPrefix) == true -> true
            destinationContainerId?.startsWith(bankPrefix) == true -> true
            sourceBankId == null && destinationBankId == null -> true
            else -> false
        }
    }

    /** Bank and masked number of an account container id, for its display label. */
    fun accountOf(containerId: String): Pair<Bank, String>? {
        val bankId = containerId.removePrefix("account:").substringBefore(':').trim().takeIf { it.isNotEmpty() } ?: return null
        val masked = FinancialContainerIdParser.accountMaskedNumber(containerId) ?: return null
        return Bank(bankId) to masked
    }

    /** Bank of the first card container among [containerIds], for a card label. */
    fun cardBankOf(containerIds: List<String?>): Bank? =
        containerIds.filterNotNull().firstOrNull { it.startsWith("card:") }?.let(FinancialContainerIdParser::cardBankId)?.let(::Bank)
}
