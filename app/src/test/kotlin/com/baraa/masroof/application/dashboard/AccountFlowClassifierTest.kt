package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccountFlowClassifierTest {
    private val accountId = "account:bank_aljazira:3001"
    private val cardId = "card:bank_aljazira:7271"
    private val scope = CurrentAccountTransactionScope(
        ownedContainerIds = setOf(accountId),
        ownedAccountLast4s = setOf("3001"),
        mode = AccountFlowScopeMode.SingleAccount,
    )

    @Test
    fun income_onOwnedDestination_classifiedAsSalaryWhenHeuristicMatches() {
        val tx = tx("salary", FinancialTransactionType.INCOME, "15000", dest = accountId, linked = listOf("evt-1"))
        val context = AccountFlowClassifier.buildContext(
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord("evt-1", MessageFamily.TRANSFER_IN)),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = mapOf(
                "sms-evt-1" to rawSms("sms-evt-1", "evt-1", "حوالة واردة راتب"),
            ),
        )

        val result = AccountFlowClassifier.classify(tx, scope, context)

        assertEquals(
            listOf(FlowAssignment.Income(FlowIncomeCategory.SALARY)),
            result,
        )
    }

    @Test
    fun expense_fromCreditCardWithoutOwnedAccount_isExcluded() {
        val tx = tx("card-exp", FinancialTransactionType.EXPENSE, "75", source = cardId)
        val result = AccountFlowClassifier.classify(tx, scope, emptyContext())

        assertTrue(result.isEmpty())
    }

    @Test
    fun expense_fromOwnedAccount_classifiedAsPosPurchase() {
        val tx = tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId)
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
            transactions = listOf(tx),
            parsedRecords = listOf(
                parsedRecord(
                    "evt-refund",
                    MessageFamily.REFUND,
                    destinationLast4 = "3001",
                    body = "استرداد مبلغ راتب إلى الحساب",
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = mapOf("sms-evt-refund" to rawSms("sms-evt-refund", "evt-refund", "استرداد مبلغ راتب")),
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
            transactions = listOf(tx),
            parsedRecords = listOf(
                parsedRecord(
                    "evt-card-account",
                    MessageFamily.REFUND,
                    destinationLast4 = "3001",
                    cardLast4 = "7271",
                    cardSmsChannel = CardSmsChannel.CREDIT,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = emptyMap(),
        )

        assertTrue(AccountFlowClassifier.classify(tx, scope, context).isEmpty())
        assertTrue(AccountFlowClassifier.classify(tx, fleetScope(), context).isEmpty())
    }

    @Test
    fun refund_toCreditCard_isExcludedFromAccountCash() {
        val tx = tx("card-refund", FinancialTransactionType.REFUND, "25", dest = cardId)
        val context = AccountFlowClassifier.buildContext(
            transactions = listOf(tx),
            parsedRecords = listOf(
                parsedRecord(
                    "evt-card-refund",
                    MessageFamily.REFUND,
                    cardLast4 = "7271",
                    cardSmsChannel = CardSmsChannel.CREDIT,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = emptyMap(),
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
        val tx = tx("other-refund", FinancialTransactionType.REFUND, "15", dest = other)

        assertTrue(AccountFlowClassifier.classify(tx, scope, emptyContext()).isEmpty())
    }

    @Test
    fun refund_toLinkedDebitCard_creditsThatCurrentAccount() {
        val debitCardId = "card:bank_aljazira:2210"
        val tx = tx("mada-refund", FinancialTransactionType.REFUND, "20", dest = debitCardId, linked = listOf("evt-mada"))
        val context = AccountFlowClassifier.buildContext(
            transactions = listOf(tx),
            parsedRecords = listOf(
                parsedRecord(
                    "evt-mada",
                    MessageFamily.REFUND,
                    cardLast4 = "2210",
                    cardSmsChannel = CardSmsChannel.DEBIT,
                ),
            ),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = emptyMap(),
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
        )
        val bothSidesScope = CurrentAccountTransactionScope(
            ownedContainerIds = setOf(accountId, "account:bank_aljazira:4002"),
            ownedAccountLast4s = setOf("3001", "4002"),
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
            transactions = listOf(tx),
            parsedRecords = listOf(parsedRecord("evt-bill", MessageFamily.BILL_PAYMENT)),
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            rawSmsById = emptyMap(),
        )

        val result = AccountFlowClassifier.classify(tx, scope, context)

        assertEquals(
            listOf(FlowAssignment.Expense(FlowExpenseCategory.BILL_PAYMENT)),
            result,
        )
    }

    private fun fleetScope() = CurrentAccountTransactionScope(
        ownedContainerIds = setOf(accountId),
        ownedAccountLast4s = setOf("3001"),
        mode = AccountFlowScopeMode.Fleet,
    )

    private fun emptyContext() = AccountFlowClassificationContext(
        parsedRecordsById = emptyMap(),
        rawSmsById = emptyMap(),
        billPaymentTxIds = emptySet(),
        primaryCurrency = Currency.SAR,
        sarEquivalents = emptyMap(),
    )

    private fun tx(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        source: String? = null,
        dest: String? = null,
        linked: List<String> = listOf("evt-$id"),
    ) = FinancialTransaction(
        id = id,
        type = type,
        amount = Money.of(amount, Currency.SAR),
        occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
        sourceContainerId = source,
        destinationContainerId = dest,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = linked,
    )

    private fun parsedRecord(
        id: String,
        family: MessageFamily,
        destinationLast4: String? = null,
        cardLast4: String? = null,
        cardSmsChannel: CardSmsChannel? = null,
        body: String? = null,
    ) = ParsedEventRecord(
        event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = family,
            direction = MoneyDirection.INCOMING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = destinationLast4?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
            cardRef = cardLast4?.let { CardReference(Bank.BANK_ALJAZIRA, it) },
            merchant = null,
            counterparty = body,
            occurredAt = Instant.parse("2026-08-01T12:00:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        details = ParsedEventDetails(cardSmsChannel = cardSmsChannel),
    )

    private fun rawSms(id: String, deviceMessageId: String, body: String) = RawSms(
        id = id,
        sender = "Bank",
        body = body,
        receivedAt = Instant.parse("2026-08-01T12:00:00Z"),
        deviceMessageId = deviceMessageId,
        bodyHash = id,
    )
}
