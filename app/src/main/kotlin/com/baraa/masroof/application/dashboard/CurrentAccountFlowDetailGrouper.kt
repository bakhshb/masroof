package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

enum class FlowExpenseCategory {
    EXTERNAL_TRANSFER_OUT,
    CREDIT_CARD_PAYMENT,
    CASH_WITHDRAWAL,
    BILL_PAYMENT,
    POS_PURCHASE,
    FEE,
    LOAN_REPAYMENT,
}

enum class FlowIncomeCategory {
    SALARY,
    EXTERNAL_TRANSFER_IN,
    ACCOUNT_REFUND,
    OTHER_INCOME,
}

data class CurrentAccountFlowDetailGrouping(
    val expense: Map<FlowExpenseCategory, List<FinancialTransaction>>,
    val income: Map<FlowIncomeCategory, List<FinancialTransaction>>,
    val selfTransfersOut: List<FinancialTransaction> = emptyList(),
    val selfTransfersIn: List<FinancialTransaction> = emptyList(),
) {
    companion object {
        val EXPENSE_DISPLAY_ORDER = listOf(
            FlowExpenseCategory.EXTERNAL_TRANSFER_OUT,
            FlowExpenseCategory.CREDIT_CARD_PAYMENT,
            FlowExpenseCategory.CASH_WITHDRAWAL,
            FlowExpenseCategory.BILL_PAYMENT,
            FlowExpenseCategory.POS_PURCHASE,
            FlowExpenseCategory.FEE,
            FlowExpenseCategory.LOAN_REPAYMENT,
        )

        val INCOME_DISPLAY_ORDER = listOf(
            FlowIncomeCategory.SALARY,
            FlowIncomeCategory.EXTERNAL_TRANSFER_IN,
            FlowIncomeCategory.ACCOUNT_REFUND,
            FlowIncomeCategory.OTHER_INCOME,
        )

        fun empty(): CurrentAccountFlowDetailGrouping = CurrentAccountFlowDetailGrouping(
            expense = FlowExpenseCategory.entries.associateWith { emptyList() },
            income = FlowIncomeCategory.entries.associateWith { emptyList() },
            selfTransfersOut = emptyList(),
            selfTransfersIn = emptyList(),
        )
    }
}

object CurrentAccountFlowDetailGrouper {
    fun group(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        ownedAccountContainerIds: Set<String> = emptySet(),
        ownedAccountLast4s: Set<String> = emptySet(),
        rawSmsById: Map<String, RawSms> = emptyMap(),
        scopeMode: AccountFlowScopeMode = AccountFlowScopeMode.Fleet,
        debitCardScope: DebitCardScopeFacts = DebitCardScopeFacts(emptySet(), emptyMap()),
    ): CurrentAccountFlowDetailGrouping {
        val context = AccountFlowClassifier.buildContext(
            transactions = transactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            rawSmsById = rawSmsById,
        )
        val scope = CurrentAccountTransactionScope(
            ownedContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            mode = scopeMode,
            ownedDebitCardContainerIds = debitCardScope.ownedDebitCardContainerIds,
            debitCardLinkedAccountIds = debitCardScope.debitCardLinkedAccountIds,
        )
        val expense = FlowExpenseCategory.entries.associateWith { mutableListOf<FinancialTransaction>() }
        val income = FlowIncomeCategory.entries.associateWith { mutableListOf<FinancialTransaction>() }
        val selfTransfersOut = mutableListOf<FinancialTransaction>()
        val selfTransfersIn = mutableListOf<FinancialTransaction>()

        for (tx in transactions) {
            if (TransactionAmountResolver.effectiveAmount(tx, primaryCurrency, sarEquivalents) == null) {
                continue
            }
            for (assignment in AccountFlowClassifier.classify(tx, scope, context)) {
                when (assignment) {
                    FlowAssignment.Excluded -> Unit

                    is FlowAssignment.Income ->
                        income.getValue(assignment.category).add(tx)

                    is FlowAssignment.Expense ->
                        expense.getValue(assignment.category).add(tx)

                    is FlowAssignment.SelfTransfer -> when (assignment.leg) {
                        SelfTransferLeg.IN -> selfTransfersIn.add(tx)
                        SelfTransferLeg.OUT -> selfTransfersOut.add(tx)
                    }
                }
            }
        }

        return CurrentAccountFlowDetailGrouping(
            expense = expense.mapValues { (_, rows) -> rows.toList() },
            income = income.mapValues { (_, rows) -> rows.toList() },
            selfTransfersOut = selfTransfersOut.toList(),
            selfTransfersIn = selfTransfersIn.toList(),
        )
    }
}
