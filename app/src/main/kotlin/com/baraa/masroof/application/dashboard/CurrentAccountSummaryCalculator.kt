package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

object CurrentAccountSummaryCalculator {
    fun summarize(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        ownedAccountContainerIds: Set<String> = emptySet(),
        ownedAccountLast4s: Set<String> = emptySet(),
        scopeMode: AccountFlowScopeMode = AccountFlowScopeMode.Fleet,
        debitCardScope: DebitCardScopeFacts = DebitCardScopeFacts(emptySet(), emptyMap()),
    ): CurrentAccountSummary =
        summarize(
            grouping = CurrentAccountFlowDetailGrouper.group(
                transactions = transactions,
                parsedRecords = parsedRecords,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
                ownedAccountContainerIds = ownedAccountContainerIds,
                ownedAccountLast4s = ownedAccountLast4s,
                scopeMode = scopeMode,
                debitCardScope = debitCardScope,
            ),
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
        )

    fun summarize(
        grouping: CurrentAccountFlowDetailGrouping,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
    ): CurrentAccountSummary {
        fun total(rows: List<FinancialTransaction>): Money =
            rows.mapNotNull { TransactionAmountResolver.effectiveAmount(it, primaryCurrency, sarEquivalents) }
                .fold(Money.zero(primaryCurrency), Money::plus)

        return CurrentAccountSummary.of(
            currency = primaryCurrency,
            salary = total(grouping.income.getValue(FlowIncomeCategory.SALARY)),
            otherIncome = total(grouping.income.getValue(FlowIncomeCategory.OTHER_INCOME)),
            externalTransfersIn = total(grouping.income.getValue(FlowIncomeCategory.EXTERNAL_TRANSFER_IN)),
            selfTransfersIn = total(grouping.selfTransfersIn),
            accountRefunds = total(grouping.income.getValue(FlowIncomeCategory.ACCOUNT_REFUND)),
            creditCardPayments = total(grouping.expense.getValue(FlowExpenseCategory.CREDIT_CARD_PAYMENT)),
            billPayments = total(grouping.expense.getValue(FlowExpenseCategory.BILL_PAYMENT)),
            externalTransfersOut = total(grouping.expense.getValue(FlowExpenseCategory.EXTERNAL_TRANSFER_OUT)),
            cashWithdrawals = total(grouping.expense.getValue(FlowExpenseCategory.CASH_WITHDRAWAL)),
            posPurchases = total(grouping.expense.getValue(FlowExpenseCategory.POS_PURCHASE)),
            fees = total(grouping.expense.getValue(FlowExpenseCategory.FEE)),
            loanRepayments = total(grouping.expense.getValue(FlowExpenseCategory.LOAN_REPAYMENT)),
            selfTransfersOut = total(grouping.selfTransfersOut),
        )
    }
}
