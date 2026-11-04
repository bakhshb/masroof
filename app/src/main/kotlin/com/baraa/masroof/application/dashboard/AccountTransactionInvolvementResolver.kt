package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.core.money.Currency
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
        debitCardScope: DebitCardScopeFacts = DebitCardScopeFacts(emptySet(), emptyMap()),
    ): Map<String, Set<String>> {
        if (transactions.isEmpty() || ownedAccounts.isEmpty()) return emptyMap()

        val parsedRecordsById = parsedRecords.associateBy { it.event.id }
        val refundContext = AccountFlowClassificationContext(parsedRecordsById, emptySet(), Currency.SAR, emptyMap())
        val scopesByContainer = ownedAccounts.map { account ->
            val containerId = FinancialContainerIdFactory.accountId(account.bank, account.maskedNumber)
            containerId to CurrentAccountTransactionScope(
                ownedContainerIds = setOf(containerId),
                mode = AccountFlowScopeMode.SingleAccount,
                ownedDebitCardContainerIds = debitCardScope.ownedDebitCardContainerIds,
                debitCardLinkedAccountIds = debitCardScope.debitCardLinkedAccountIds,
            )
        }.toMap()

        return transactions.associate { tx ->
            tx.id to scopesByContainer.filter { (_, scope) ->
                if (tx.type == FinancialTransactionType.REFUND) {
                    // Match account-flow attribution, including verified linked debit refunds.
                    AccountFlowClassifier.classifyWithAmount(tx, scope, refundContext).isNotEmpty()
                } else {
                    scope.involvesOwnedAccount(tx, parsedRecordsById)
                }
            }.keys
        }
    }
}
