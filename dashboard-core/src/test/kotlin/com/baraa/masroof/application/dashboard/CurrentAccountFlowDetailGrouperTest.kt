package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.testsupport.tx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CurrentAccountFlowDetailGrouperTest {
    @Test
    fun groupsExpenseTransactionsIntoMatchingCategories() {
        val accountId = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:7271"
        val transactions = listOf(
            tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountId),
            tx("card-pay", FinancialTransactionType.CREDIT_CARD_PAYMENT, "500", source = accountId, dest = cardId),
            tx("cash", FinancialTransactionType.CASH_WITHDRAWAL, "50", source = accountId),
            tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId),
            tx("card-exp", FinancialTransactionType.EXPENSE, "75", source = cardId),
        )

        val grouping = CurrentAccountFlowDetailGrouper.group(
            transactions = transactions,
            parsedRecords = emptyList(),
        )

        assertEquals(listOf("xfer-out"), ids(grouping, FlowExpenseCategory.EXTERNAL_TRANSFER_OUT))
        assertEquals(listOf("card-pay"), ids(grouping, FlowExpenseCategory.CREDIT_CARD_PAYMENT))
        assertEquals(listOf("cash"), ids(grouping, FlowExpenseCategory.CASH_WITHDRAWAL))
        assertEquals(listOf("pos"), ids(grouping, FlowExpenseCategory.POS_PURCHASE))
        assertEquals(emptyList<String>(), ids(grouping, FlowExpenseCategory.BILL_PAYMENT))
        assertEquals(emptyList<String>(), ids(grouping, FlowExpenseCategory.FEE))
    }

    @Test
    fun groupsIncomeTransactionsIntoSalaryAndTransfers() {
        val accountId = "account:bank_aljazira:3001"
        val transactions = listOf(
            tx("income", FinancialTransactionType.INCOME, "15000", dest = accountId),
            tx("xfer-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "200", dest = accountId),
        )

        val grouping = CurrentAccountFlowDetailGrouper.group(
            transactions = transactions,
            parsedRecords = emptyList(),
        )

        assertEquals(listOf("income"), ids(grouping, FlowIncomeCategory.SALARY))
        assertEquals(listOf("xfer-in"), ids(grouping, FlowIncomeCategory.EXTERNAL_TRANSFER_IN))
        assertEquals(emptyList<String>(), ids(grouping, FlowIncomeCategory.OTHER_INCOME))
    }

    @Test
    fun groupedExpenseTotalsMatchSummaryCalculator() {
        val accountId = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:7271"
        val transactions = listOf(
            tx("income", FinancialTransactionType.INCOME, "15000", dest = accountId),
            tx("xfer-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "200", dest = accountId),
            tx("card-pay", FinancialTransactionType.CREDIT_CARD_PAYMENT, "500", source = accountId, dest = cardId),
            tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountId),
            tx("cash", FinancialTransactionType.CASH_WITHDRAWAL, "50", source = accountId),
            tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId),
            tx("card-exp", FinancialTransactionType.EXPENSE, "75", source = cardId),
        )

        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = transactions,
            parsedRecords = emptyList(),
        )
        val grouping = CurrentAccountFlowDetailGrouper.group(
            transactions = transactions,
            parsedRecords = emptyList(),
        )

        assertEquals(
            summary.outflow.externalTransfersOut,
            sumGrouped(grouping, FlowExpenseCategory.EXTERNAL_TRANSFER_OUT),
        )
        assertEquals(
            summary.outflow.creditCardPayments,
            sumGrouped(grouping, FlowExpenseCategory.CREDIT_CARD_PAYMENT),
        )
        assertEquals(
            summary.outflow.cashWithdrawals,
            sumGrouped(grouping, FlowExpenseCategory.CASH_WITHDRAWAL),
        )
        assertEquals(
            summary.outflow.posPurchases,
            sumGrouped(grouping, FlowExpenseCategory.POS_PURCHASE),
        )
    }

    @Test
    fun groupsSelfTransfersSeparatelyFromCoreExpenseCategories() {
        val accountA = "account:bank_aljazira:3001"
        val accountB = "account:bank_aljazira:3002"
        val transactions = listOf(
            tx(
                id = "self",
                type = FinancialTransactionType.SELF_TRANSFER,
                amount = "500",
                source = accountA,
                dest = accountB,
            ),
            tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountA),
        )

        val grouping = CurrentAccountFlowDetailGrouper.group(
            transactions = transactions,
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountA, accountB),
        )

        assertEquals(listOf("self"), grouping.selfTransfersOut.map { it.id })
        assertEquals(listOf("self"), grouping.selfTransfersIn.map { it.id })
        assertEquals(listOf("xfer-out"), ids(grouping, FlowExpenseCategory.EXTERNAL_TRANSFER_OUT))
    }

    private fun ids(
        grouping: CurrentAccountFlowDetailGrouping,
        category: FlowExpenseCategory,
    ): List<String> = grouping.expense[category].orEmpty().map { it.id }

    private fun ids(
        grouping: CurrentAccountFlowDetailGrouping,
        category: FlowIncomeCategory,
    ): List<String> = grouping.income[category].orEmpty().map { it.id }

    private fun sumGrouped(
        grouping: CurrentAccountFlowDetailGrouping,
        category: FlowExpenseCategory,
    ): Money = grouping.expense[category].orEmpty()
        .fold(Money.zero(Currency.SAR)) { acc, tx -> acc + tx.amount }
}
