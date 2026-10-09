package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

object CurrentAccountSummaryCalculator {
    fun summarize(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        ownedAccountContainerIds: Set<String> = emptySet(),
        ownedAccountLast4s: Set<String> = emptySet(),
        rawSmsById: Map<String, RawSms> = emptyMap(),
        scopeMode: AccountFlowScopeMode = AccountFlowScopeMode.Fleet,
        debitCardScope: DebitCardScopeFacts = DebitCardScopeFacts(emptySet(), emptyMap()),
    ): CurrentAccountSummary {
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

        var salary = Money.zero(primaryCurrency)
        var otherIncome = Money.zero(primaryCurrency)
        var externalTransfersIn = Money.zero(primaryCurrency)
        var accountRefunds = Money.zero(primaryCurrency)
        var selfTransfersIn = Money.zero(primaryCurrency)
        var selfTransfersOut = Money.zero(primaryCurrency)
        var creditCardPayments = Money.zero(primaryCurrency)
        var billPayments = Money.zero(primaryCurrency)
        var externalTransfersOut = Money.zero(primaryCurrency)
        var cashWithdrawals = Money.zero(primaryCurrency)
        var posPurchases = Money.zero(primaryCurrency)
        var fees = Money.zero(primaryCurrency)
        var loanRepayments = Money.zero(primaryCurrency)

        for (tx in transactions) {
            val amount = TransactionAmountResolver.effectiveAmount(
                tx,
                primaryCurrency,
                sarEquivalents,
            ) ?: continue
            for (assignment in AccountFlowClassifier.classify(tx, scope, context)) {
                when (assignment) {
                    FlowAssignment.Excluded -> Unit

                    is FlowAssignment.Income -> when (assignment.category) {
                        FlowIncomeCategory.SALARY -> salary += amount
                        FlowIncomeCategory.OTHER_INCOME -> otherIncome += amount
                        FlowIncomeCategory.EXTERNAL_TRANSFER_IN -> externalTransfersIn += amount
                        FlowIncomeCategory.ACCOUNT_REFUND -> accountRefunds += amount
                    }

                    is FlowAssignment.Expense -> when (assignment.category) {
                        FlowExpenseCategory.EXTERNAL_TRANSFER_OUT -> externalTransfersOut += amount
                        FlowExpenseCategory.CREDIT_CARD_PAYMENT -> creditCardPayments += amount
                        FlowExpenseCategory.CASH_WITHDRAWAL -> cashWithdrawals += amount
                        FlowExpenseCategory.BILL_PAYMENT -> billPayments += amount
                        FlowExpenseCategory.POS_PURCHASE -> posPurchases += amount
                        FlowExpenseCategory.FEE -> fees += amount
                        FlowExpenseCategory.LOAN_REPAYMENT -> loanRepayments += amount
                    }

                    is FlowAssignment.SelfTransfer -> when (assignment.leg) {
                        SelfTransferLeg.IN -> selfTransfersIn += amount
                        SelfTransferLeg.OUT -> selfTransfersOut += amount
                    }
                }
            }
        }

        return CurrentAccountSummary.of(
            currency = primaryCurrency,
            salary = salary,
            otherIncome = otherIncome,
            externalTransfersIn = externalTransfersIn,
            selfTransfersIn = selfTransfersIn,
            accountRefunds = accountRefunds,
            creditCardPayments = creditCardPayments,
            billPayments = billPayments,
            externalTransfersOut = externalTransfersOut,
            cashWithdrawals = cashWithdrawals,
            posPurchases = posPurchases,
            fees = fees,
            loanRepayments = loanRepayments,
            selfTransfersOut = selfTransfersOut,
        )
    }

    fun spendingSplit(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        ownedAccountContainerIds: Set<String> = emptySet(),
        ownedAccountLast4s: Set<String> = emptySet(),
        rawSmsById: Map<String, RawSms> = emptyMap(),
        debitCardScope: DebitCardScopeFacts = DebitCardScopeFacts(emptySet(), emptyMap()),
    ): SpendingSplitSummary {
        val currentAccount = summarize(
            transactions = transactions,
            parsedRecords = parsedRecords,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            ownedAccountContainerIds = ownedAccountContainerIds,
            ownedAccountLast4s = ownedAccountLast4s,
            rawSmsById = rawSmsById,
            debitCardScope = debitCardScope,
        )

        var cardGross = Money.zero(primaryCurrency)
        var cardRefunds = Money.zero(primaryCurrency)

        for (tx in transactions) {
            val amount = TransactionAmountResolver.effectiveAmount(tx, primaryCurrency, sarEquivalents)
                ?: continue
            when (tx.type) {
                FinancialTransactionType.EXPENSE,
                FinancialTransactionType.BILL_PAYMENT,
                FinancialTransactionType.FEE,
                -> {
                    val sourceId = tx.sourceContainerId
                    if (sourceId != null &&
                        sourceId in debitCardScope.ownedDebitCardContainerIds
                    ) {
                        continue
                    }
                    if (TransactionAmountResolver.isCreditCardContainer(sourceId)) {
                        cardGross += amount
                    }
                }

                FinancialTransactionType.REFUND -> {
                    val destinationId = tx.destinationContainerId
                    if (destinationId != null &&
                        destinationId in debitCardScope.ownedDebitCardContainerIds
                    ) {
                        continue
                    }
                    if (TransactionAmountResolver.isCreditCardContainer(destinationId)) {
                        cardRefunds += amount
                    }
                }

                else -> Unit
            }
        }

        return SpendingSplitSummary(
            currency = primaryCurrency,
            totalSpending = currentAccount.outflow.coreTotal,
            creditCardPurchases = SignedMoneyAmount.difference(cardGross, cardRefunds),
        )
    }
}
