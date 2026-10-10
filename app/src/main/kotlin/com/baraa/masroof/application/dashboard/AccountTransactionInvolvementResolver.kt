package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/**
 * Maps each transaction to owned account container IDs it touches, using the same
 * parsed-account resolution as [CurrentAccountSummaryCalculator] (SingleAccount scope).
 */
object AccountTransactionInvolvementResolver {
    fun buildIndex(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        ownedAccounts: List<AccountRegistryEntry>,
    ): Map<String, Set<String>> {
        if (transactions.isEmpty() || ownedAccounts.isEmpty()) return emptyMap()

        val parsedRecordsById = parsedRecords.associateBy { it.event.id }
        val scopesByContainer = ownedAccounts.mapNotNull { account ->
            val containerId = FinancialContainerIdFactory.accountId(account.bank, account.maskedNumber)
                ?: return@mapNotNull null
            val last4s = CurrentAccountTransactionScope.ownedAccountLast4sFromMaskedNumbers(
                listOf(account.maskedNumber),
            )
            containerId to CurrentAccountTransactionScope(
                ownedContainerIds = setOf(containerId),
                ownedAccountLast4s = last4s,
                mode = AccountFlowScopeMode.SingleAccount,
            )
        }.toMap()

        return transactions.associate { tx ->
            tx.id to scopesByContainer.filter { (_, scope) ->
                scope.involvesOwnedAccount(tx, parsedRecordsById)
            }.keys
        }
    }
}
