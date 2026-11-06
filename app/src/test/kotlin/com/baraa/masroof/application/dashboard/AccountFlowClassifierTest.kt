package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.testsupport.parsedRecord
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFlowClassifierTest {
    private val accountId = "account:bank_aljazira:3001"
    private val cardId = "card:bank_aljazira:7271"
    private val scope = CurrentAccountTransactionScope(
        ownedContainerIds = setOf(accountId),
        mode = AccountFlowScopeMode.SingleAccount,
    )

    @Test
    fun income_onOwnedDestination_classifiedAsSalaryWhenHeuristicMatches() {
        val tx = tx("salary", FinancialTransactionType.INCOME, "15000", dest = accountId, linked = listOf("evt-1"))
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(parsedRecord("evt-1", MessageFamily.TRANSFER_IN, direction = MoneyDirection.INCOMING)),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )

        val result = AccountFlowClassifier.classify(tx, scope, context)

        assertEquals(
            listOf(FlowAssignment.Income(FlowIncomeCategory.SALARY)),
            result,
        )
    }

    @Test
    fun expense_fromCreditCardWithoutOwnedAccount_isExcluded() {
        val tx = tx("card-exp", FinancialTransactionType.EXPENSE, "75", source = cardId, linked = listOf("evt-card-exp"))
        val result = AccountFlowClassifier.classify(tx, scope, emptyContext())

        assertTrue(result.isEmpty())
    }

    @Test
    fun expense_fromOwnedAccount_classifiedAsPosPurchase() {
        val tx = tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId, linked = listOf("evt-pos"))
        val result = AccountFlowClassifier.classify(tx, scope, emptyContext())

        assertEquals(
            listOf(FlowAssignment.Expense(FlowExpenseCategory.POS_PURCHASE)),
            result,
        )
    }

    @Test
    fun refund_toOwnedCurrentAccount_isAccountRefundNotIncome() {
        val tx = tx(
            "refund",
            FinancialTransactionType.REFUND,
            "100",
            dest = accountId,
            linked = listOf("evt-refund"),
        )
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(
                parsedRecord(
                    "evt-refund",
                    MessageFamily.REFUND,
                    destinationLast4 = "3001",
                    body = "استرداد مبلغ راتب إلى الحساب",
                    direction = MoneyDirection.INCOMING,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )

        val result = AccountFlowClassifier.classify(tx, scope, context)

        assertEquals(
            listOf(FlowAssignment.Income(FlowIncomeCategory.ACCOUNT_REFUND)),
            result,
        )
    }

    @Test
    fun refund_creditCardWithAccountDestination_doesNotIncreaseAccountCash() {
        val tx = tx(
            "card-refund-account",
            FinancialTransactionType.REFUND,
            "25",
            dest = accountId,
            linked = listOf("evt-card-account"),
        )
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(
                parsedRecord(
                    "evt-card-account",
                    MessageFamily.REFUND,
                    destinationLast4 = "3001",
                    cardLast4 = "7271",
                    cardSmsChannel = CardSmsChannel.CREDIT,
                    direction = MoneyDirection.INCOMING,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )

        assertTrue(AccountFlowClassifier.classify(tx, scope, context).isEmpty())
        assertTrue(AccountFlowClassifier.classify(tx, fleetScope(), context).isEmpty())
    }

    @Test
    fun refund_toCreditCard_isExcludedFromAccountCash() {
        val tx = tx("card-refund", FinancialTransactionType.REFUND, "25", dest = cardId, linked = listOf("evt-card-refund"))
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(
                parsedRecord(
                    "evt-card-refund",
                    MessageFamily.REFUND,
                    cardLast4 = "7271",
                    cardSmsChannel = CardSmsChannel.CREDIT,
                    direction = MoneyDirection.INCOMING,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )

        assertTrue(AccountFlowClassifier.classify(tx, scope, context).isEmpty())
        assertTrue(AccountFlowClassifier.classify(tx, fleetScope(), context).isEmpty())
    }

    @Test
    fun refund_unknownDestination_staysUnattributed() {
        val tx = tx("unknown-refund", FinancialTransactionType.REFUND, "40", dest = null, linked = emptyList())

        assertTrue(AccountFlowClassifier.classify(tx, scope, emptyContext()).isEmpty())
        assertTrue(AccountFlowClassifier.classify(tx, fleetScope(), emptyContext()).isEmpty())
    }

    @Test
    fun refund_toAnotherOwnedAccount_isExcludedFromThisAccount() {
        val other = "account:bank_aljazira:3002"
        val tx = tx("other-refund", FinancialTransactionType.REFUND, "15", dest = other, linked = listOf("evt-other-refund"))

        assertTrue(AccountFlowClassifier.classify(tx, scope, emptyContext()).isEmpty())
    }

    @Test
    fun refund_toLinkedDebitCard_creditsThatCurrentAccount() {
        val debitCardId = "card:bank_aljazira:2210"
        val tx = tx("mada-refund", FinancialTransactionType.REFUND, "20", dest = debitCardId, linked = listOf("evt-mada"))
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(
                parsedRecord(
                    "evt-mada",
                    MessageFamily.REFUND,
                    cardLast4 = "2210",
                    cardSmsChannel = CardSmsChannel.DEBIT,
                    direction = MoneyDirection.INCOMING,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )
        val debitScope = scope.copy(
            ownedDebitCardContainerIds = setOf(debitCardId),
            debitCardLinkedAccountIds = mapOf(debitCardId to accountId),
        )

        assertEquals(
            listOf(FlowAssignment.Income(FlowIncomeCategory.ACCOUNT_REFUND)),
            AccountFlowClassifier.classify(tx, debitScope, context),
        )
    }

    @Test
    fun selfTransfer_onOwnedBothSides_emitsInAndOut() {
        val tx = tx(
            "xfer",
            FinancialTransactionType.SELF_TRANSFER,
            "500",
            source = accountId,
            dest = "account:bank_aljazira:4002",
            linked = listOf("evt-xfer"),
        )
        val bothSidesScope = CurrentAccountTransactionScope(
            ownedContainerIds = setOf(accountId, "account:bank_aljazira:4002"),
            mode = AccountFlowScopeMode.Fleet,
        )

        val result = AccountFlowClassifier.classify(tx, bothSidesScope, emptyContext())

        assertEquals(
            listOf(
                FlowAssignment.SelfTransfer(SelfTransferLeg.IN),
                FlowAssignment.SelfTransfer(SelfTransferLeg.OUT),
            ),
            result,
        )
    }

    @Test
    fun billPaymentExpense_usesBillPaymentCategory() {
        val tx = tx("bill", FinancialTransactionType.EXPENSE, "120", source = accountId, linked = listOf("evt-bill"))
        val context = AccountFlowClassifier.buildContext(
            parsedRecords = listOf(parsedRecord("evt-bill", MessageFamily.BILL_PAYMENT, direction = MoneyDirection.INCOMING)),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
        )

        val result = AccountFlowClassifier.classify(tx, scope, context)

        assertEquals(
            listOf(FlowAssignment.Expense(FlowExpenseCategory.BILL_PAYMENT)),
            result,
        )
    }

    @Test
    fun refund_sameSuffixInDifferentBank_isExcludedFromSelectedAccountAndFleet() {
        val refund = tx("foreign-refund", FinancialTransactionType.REFUND, "100", dest = "account:OTHER_BANK:3001", linked = listOf("evt-foreign-refund"))
        assertTrue(AccountFlowClassifier.classify(refund, scope, emptyContext()).isEmpty())
        assertTrue(AccountFlowClassifier.classify(refund, fleetScope(), emptyContext()).isEmpty())
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(refund),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountId),
        )
        assertEquals(Money.zero(Currency.SAR), summary.inflow.accountRefunds)
    }

    @Test
    fun refund_persistedForeignAccountFact_doesNotMatchOwnedSuffix() {
        val refund = tx("fact-refund", FinancialTransactionType.REFUND, "100", linked = listOf("foreign"))
        val original = parsedRecord("foreign", MessageFamily.REFUND, destinationLast4 = "3001", direction = MoneyDirection.INCOMING)
        val foreign = original.copy(event = original.event.copy(
            bank = Bank("OTHER_BANK"),
            destinationAccountRef = AccountReference(Bank("OTHER_BANK"), "3001"),
        ))
        val context = emptyContext().copy(parsedRecordsById = mapOf("foreign" to foreign))
        assertTrue(AccountFlowClassifier.classify(refund, scope, context).isEmpty())
    }

    @Test
    fun refund_debitCardLinkedToForeignSameSuffix_doesNotCreditSelectedAccount() {
        val debitCard = "card:OTHER_BANK:2210"
        val refund = tx("foreign-card", FinancialTransactionType.REFUND, "20", dest = debitCard, linked = listOf("evt-foreign-card"))
        val linkedScope = scope.copy(
            ownedDebitCardContainerIds = setOf(debitCard),
            debitCardLinkedAccountIds = mapOf(debitCard to "account:OTHER_BANK:3001"),
        )
        assertTrue(AccountFlowClassifier.classify(refund, linkedScope, emptyContext()).isEmpty())
    }

    @Test
    fun refund_bankIdentityCaseDifference_isStillTheSameAccount() {
        val refund = tx("same-bank", FinancialTransactionType.REFUND, "20", dest = "account:BANK_ALJAZIRA:3001", linked = listOf("evt-same-bank"))
        assertEquals(
            listOf(FlowAssignment.Income(FlowIncomeCategory.ACCOUNT_REFUND)),
            AccountFlowClassifier.classify(refund, scope, emptyContext()),
        )
    }

    @Test
    fun refund_unqualifiedDestination_cannotCertifyBankIdentity() {
        val refund = tx("legacy", FinancialTransactionType.REFUND, "20", dest = "account:3001", linked = listOf("evt-legacy"))
        assertTrue(AccountFlowClassifier.classify(refund, scope, emptyContext()).isEmpty())
    }

    private fun fleetScope() = CurrentAccountTransactionScope(
        ownedContainerIds = setOf(accountId),
        mode = AccountFlowScopeMode.Fleet,
    )

    private fun emptyContext() = AccountFlowClassificationContext(
        parsedRecordsById = emptyMap(),
        primaryCurrency = Currency.SAR,
        sarEquivalents = emptyMap(),
    )
}
