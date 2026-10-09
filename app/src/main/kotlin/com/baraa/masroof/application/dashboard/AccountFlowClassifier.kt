package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.isCreditCardSms
import com.baraa.masroof.parsing.model.isDebitCardSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord

enum class SelfTransferLeg {
    IN,
    OUT,
}

sealed interface FlowAssignment {
    data class Expense(val category: FlowExpenseCategory) : FlowAssignment

    data class Income(val category: FlowIncomeCategory) : FlowAssignment

    data class SelfTransfer(val leg: SelfTransferLeg) : FlowAssignment

    data object Excluded : FlowAssignment
}

data class AccountFlowClassificationContext(
    val parsedRecordsById: Map<String, ParsedEventRecord>,
    val rawSmsById: Map<String, RawSms>,
    val billPaymentTxIds: Set<String>,
    val primaryCurrency: Currency,
    val sarEquivalents: Map<String, Money>,
)

object AccountFlowClassifier {
    fun classify(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): List<FlowAssignment> {
        if (
            TransactionAmountResolver.effectiveAmount(
                tx = tx,
                primaryCurrency = context.primaryCurrency,
                sarEquivalents = context.sarEquivalents,
            ) == null
        ) {
            return emptyList()
        }

        return classifyWithAmount(tx, scope, context)
    }

    internal fun classifyWithAmount(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): List<FlowAssignment> {
        val parsedRecordsById = context.parsedRecordsById
        val rawSmsById = context.rawSmsById
        val billPaymentTxIds = context.billPaymentTxIds

        return when (tx.type) {
            FinancialTransactionType.INCOME -> {
                if (!scope.involvesOwnedDestination(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                listOf(
                    if (SalaryIncomeHeuristics.isSalaryIncome(tx, parsedRecordsById)) {
                        FlowAssignment.Income(FlowIncomeCategory.SALARY)
                    } else {
                        FlowAssignment.Income(FlowIncomeCategory.OTHER_INCOME)
                    },
                )
            }

            FinancialTransactionType.EXTERNAL_TRANSFER_IN -> {
                if (!scope.involvesOwnedDestination(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                listOf(
                    if (SalaryIncomeHeuristics.isSalaryIncome(tx, parsedRecordsById)) {
                        FlowAssignment.Income(FlowIncomeCategory.SALARY)
                    } else {
                        FlowAssignment.Income(FlowIncomeCategory.EXTERNAL_TRANSFER_IN)
                    },
                )
            }

            FinancialTransactionType.CREDIT_CARD_PAYMENT ->
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    emptyList()
                } else {
                    listOf(FlowAssignment.Expense(FlowExpenseCategory.CREDIT_CARD_PAYMENT))
                }

            FinancialTransactionType.EXTERNAL_TRANSFER_OUT ->
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    emptyList()
                } else {
                    listOf(FlowAssignment.Expense(FlowExpenseCategory.EXTERNAL_TRANSFER_OUT))
                }

            FinancialTransactionType.CASH_WITHDRAWAL ->
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    emptyList()
                } else {
                    listOf(FlowAssignment.Expense(FlowExpenseCategory.CASH_WITHDRAWAL))
                }

            FinancialTransactionType.BILL_PAYMENT ->
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    emptyList()
                } else {
                    listOf(FlowAssignment.Expense(FlowExpenseCategory.BILL_PAYMENT))
                }

            FinancialTransactionType.LOAN_REPAYMENT ->
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    emptyList()
                } else {
                    listOf(FlowAssignment.Expense(FlowExpenseCategory.LOAN_REPAYMENT))
                }

            FinancialTransactionType.EXPENSE -> {
                if (scope.isCreditCardSourcedExpenseWithoutOwnedAccount(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                listOf(
                    when {
                        scope.isCreditCardPayment(tx, parsedRecordsById, rawSmsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.CREDIT_CARD_PAYMENT)

                        scope.isCashWithdrawal(tx, parsedRecordsById, rawSmsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.CASH_WITHDRAWAL)

                        scope.isBillPayment(tx, billPaymentTxIds, parsedRecordsById, rawSmsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.BILL_PAYMENT)

                        else -> FlowAssignment.Expense(FlowExpenseCategory.POS_PURCHASE)
                    },
                )
            }

            FinancialTransactionType.FEE -> {
                if (scope.isCreditCardSourcedExpenseWithoutOwnedAccount(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                if (!scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    return emptyList()
                }
                listOf(
                    when {
                        scope.isFinancingInstallment(tx, parsedRecordsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.LOAN_REPAYMENT)

                        scope.isBillPayment(tx, billPaymentTxIds, parsedRecordsById, rawSmsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.BILL_PAYMENT)

                        scope.isCashWithdrawal(tx, parsedRecordsById, rawSmsById) ->
                            FlowAssignment.Expense(FlowExpenseCategory.CASH_WITHDRAWAL)

                        else -> FlowAssignment.Expense(FlowExpenseCategory.FEE)
                    },
                )
            }

            FinancialTransactionType.SELF_TRANSFER -> buildList {
                if (scope.involvesOwnedDestination(tx, parsedRecordsById, rawSmsById)) {
                    add(FlowAssignment.SelfTransfer(SelfTransferLeg.IN))
                }
                if (scope.involvesOwnedSource(tx, parsedRecordsById, rawSmsById)) {
                    add(FlowAssignment.SelfTransfer(SelfTransferLeg.OUT))
                }
            }

            FinancialTransactionType.REFUND -> classifyRefund(tx, scope, context)

            FinancialTransactionType.ADJUSTMENT,
            FinancialTransactionType.UNKNOWN,
            -> emptyList()
        }
    }

    /**
     * A refund credited to a verified current account is cash inflow.
     * It is not salary and not ordinary income.
     * A credit-card refund offsets card liability and does not increase account cash.
     * A refund with no verified account destination stays unattributed.
     */
    private fun classifyRefund(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): List<FlowAssignment> {
        val accountId = verifiedCurrentAccountCredit(tx, scope, context) ?: return emptyList()
        if (!creditsScopedAccount(scope, accountId)) return emptyList()
        return listOf(FlowAssignment.Income(FlowIncomeCategory.ACCOUNT_REFUND))
    }

    private fun verifiedCurrentAccountCredit(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): String? {
        directAccountDestination(tx, context)?.let { return it }
        return linkedDebitAccount(tx, scope, context)
    }

    private fun directAccountDestination(
        tx: FinancialTransaction,
        context: AccountFlowClassificationContext,
    ): String? {
        tx.destinationContainerId?.takeIf { isAccountContainer(it) }?.let { return it }
        return linkedRecords(tx, context).firstNotNullOfOrNull { record ->
            record.event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId)
        }
    }

    /**
     * A debit-card refund with a registry link credits that current account.
     * Credit-card channel and an unlinked card destination do not.
     */
    private fun linkedDebitAccount(
        tx: FinancialTransaction,
        scope: CurrentAccountTransactionScope,
        context: AccountFlowClassificationContext,
    ): String? {
        val cardId = tx.destinationContainerId?.takeIf { isCardContainer(it) } ?: return null
        val records = linkedRecords(tx, context)
        if (records.any { it.details.isCreditCardSms() }) return null
        val debitCard = cardId in scope.ownedDebitCardContainerIds ||
            records.any { it.details.isDebitCardSms() }
        if (!debitCard) return null
        return scope.debitCardLinkedAccountIds[cardId]
    }

    private fun creditsScopedAccount(
        scope: CurrentAccountTransactionScope,
        accountId: String,
    ): Boolean {
        if (scope.ownedContainerIds.isEmpty()) return true
        if (accountId in scope.ownedContainerIds) return true
        if (!isAccountContainer(accountId)) return false
        return accountId.substringAfterLast(':') in scope.ownedAccountLast4s
    }

    private fun linkedRecords(
        tx: FinancialTransaction,
        context: AccountFlowClassificationContext,
    ): List<ParsedEventRecord> =
        tx.linkedParsedEventIds.mapNotNull { context.parsedRecordsById[it] }

    private fun isAccountContainer(containerId: String): Boolean =
        containerId.startsWith("account:")

    private fun isCardContainer(containerId: String): Boolean =
        containerId.startsWith("card:")

    fun resolveBillPaymentTransactionIds(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
    ): Set<String> {
        val familyByEventId = parsedRecords.associate { it.event.id to it.event.messageFamily }
        return transactions.mapNotNull { tx ->
            val families = tx.linkedParsedEventIds.mapNotNull { familyByEventId[it] }
            if (families.any { it == MessageFamily.BILL_PAYMENT }) tx.id else null
        }.toSet()
    }

    fun buildContext(
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        rawSmsById: Map<String, RawSms>,
    ): AccountFlowClassificationContext =
        AccountFlowClassificationContext(
            parsedRecordsById = parsedRecords.associateBy { it.event.id },
            rawSmsById = rawSmsById,
            billPaymentTxIds = resolveBillPaymentTransactionIds(transactions, parsedRecords),
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
        )
}
