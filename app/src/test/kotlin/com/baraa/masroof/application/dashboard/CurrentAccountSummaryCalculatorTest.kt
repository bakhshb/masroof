package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CurrentAccountSummaryCalculatorTest {
    @Test
    fun splitsAccountFlowsAndExcludesCreditCardPurchases() {
        val accountId = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:7271"
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(
                tx("income", FinancialTransactionType.INCOME, "15000", source = accountId),
                tx("xfer-in", FinancialTransactionType.EXTERNAL_TRANSFER_IN, "200", dest = accountId),
                tx("card-pay", FinancialTransactionType.CREDIT_CARD_PAYMENT, "500", source = accountId, dest = cardId),
                tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountId),
                tx("cash", FinancialTransactionType.CASH_WITHDRAWAL, "50", source = accountId),
                tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId),
                tx("card-exp", FinancialTransactionType.EXPENSE, "75", source = cardId),
            ),
            parsedRecords = emptyList(),
        )

        assertEquals(Money.of("15000.00", Currency.SAR), summary.inflow.salary)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.otherIncome)
        assertEquals(Money.of("200.00", Currency.SAR), summary.inflow.externalTransfersIn)
        assertEquals(Money.of("500.00", Currency.SAR), summary.outflow.creditCardPayments)
        assertEquals(Money.of("100.00", Currency.SAR), summary.outflow.externalTransfersOut)
        assertEquals(Money.of("50.00", Currency.SAR), summary.outflow.cashWithdrawals)
        assertEquals(Money.of("90.00", Currency.SAR), summary.outflow.posPurchases)
        assertEquals(
            SignedMoneyAmount.of(Money.of("14460.00", Currency.SAR)),
            summary.netMovement,
        )
    }

    @Test
    fun salaryTransferDetectedFromSmsWording() {
        val accountId = "account:bank_aljazira:3001"
        val salaryTx = tx(
            id = "salary-xfer",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            amount = "3191.68",
            dest = accountId,
            linked = listOf("evt-salary"),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(salaryTx),
            parsedRecords = listOf(
                parsedRecord(
                    "evt-salary",
                    MessageFamily.TRANSFER_IN,
                    salaryIncomeWording = true,
                ),
            ),
            rawSmsById = mapOf(
                "sms-evt-salary" to RawSms(
                    id = "sms-evt-salary",
                    sender = "AlJazira",
                    body = "حوالة واردة راتب\nمبلغ: SAR 3,191.68",
                    receivedAt = Instant.parse("2026-07-27T01:12:00Z"),
                    deviceMessageId = "evt-salary",
                    bodyHash = "evt-salary",
                ),
            ),
        )

        assertEquals(Money.of("3191.68", Currency.SAR), summary.inflow.salary)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.externalTransfersIn)
    }

    @Test
    fun billPayment_autoAssemblesToBillPaymentType() {
        val owned = "account:bank_aljazira:3001"
        val billTx = tx(
            id = "bill",
            type = FinancialTransactionType.BILL_PAYMENT,
            amount = "210",
            source = owned,
            linked = listOf("evt-bill"),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(billTx),
            parsedRecords = listOf(
                parsedRecord("evt-bill", MessageFamily.BILL_PAYMENT),
            ),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
        )
        assertEquals(Money.of("210.00", Currency.SAR), summary.outflow.billPayments)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.fees)
    }

    @Test
    fun feeWithBillPaymentWording_countsAsBillPayment() {
        val owned = "account:bank_aljazira:3001"
        val feeBill = tx(
            id = "fee-bill",
            type = FinancialTransactionType.FEE,
            amount = "120.00",
            source = owned,
            linked = listOf("evt-fee-bill"),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(feeBill),
            parsedRecords = listOf(
                parsedRecord(
                    id = "evt-fee-bill",
                    family = MessageFamily.BILL_PAYMENT,
                    sourceLast4 = "3001",
                    rawBody = "سداد فاتورة\nالمفوتر: STC",
                ),
            ),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            rawSmsById = mapOf(
                "sms-evt-fee-bill" to RawSms(
                    id = "sms-evt-fee-bill",
                    sender = "AlJazira",
                    body = "سداد فاتورة\nالمفوتر: STC",
                    receivedAt = Instant.parse("2026-08-10T12:00:00Z"),
                    deviceMessageId = "evt-fee-bill",
                    bodyHash = "evt-fee-bill",
                ),
            ),
        )
        assertEquals(Money.of("120.00", Currency.SAR), summary.outflow.billPayments)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.fees)
    }

    @Test
    fun billPaymentDetectedFromLinkedParsedEvent() {
        val accountId = "account:bank_aljazira:3001"
        val billTx = tx("bill", FinancialTransactionType.EXPENSE, "210", source = accountId, linked = listOf("evt-bill"))
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(billTx),
            parsedRecords = listOf(
                parsedRecord("evt-bill", MessageFamily.BILL_PAYMENT),
            ),
        )
        assertEquals(Money.of("210.00", Currency.SAR), summary.outflow.billPayments)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.posPurchases)
    }

    @Test
    fun cashWithdrawalWithNullSourceContainer_ignoredWhenScopedToSingleAccount() {
        val owned = "account:bank_aljazira:3478"
        val cashWithdrawal = tx(
            id = "cash-withdrawal",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "2200.00",
            source = null,
            linked = emptyList(),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(cashWithdrawal),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3478"),
            rawSmsById = emptyMap(),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.zero(Currency.SAR), summary.outflow.cashWithdrawals)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.total)
    }

    @Test
    fun orphanOutflow_notDuplicatedAcrossOwnedAccounts() {
        val accountA = "account:bank_aljazira:3001"
        val accountB = "account:bank_aljazira:3002"
        val orphanWithdrawal = tx(
            id = "orphan-cash",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "100.00",
            source = null,
            linked = emptyList(),
        )
        val summaryA = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(orphanWithdrawal),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountA),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        val summaryB = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(orphanWithdrawal),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountB),
            ownedAccountLast4s = setOf("3002"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.zero(Currency.SAR), summaryA.outflow.cashWithdrawals)
        assertEquals(Money.zero(Currency.SAR), summaryB.outflow.cashWithdrawals)
    }

    @Test
    fun accountRemaining_includesSelfTransfersInPerAccountCashPosition() {
        val accountA = "account:bank_aljazira:3001"
        val accountB = "account:bank_aljazira:3002"
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(
                tx(
                    id = "transfer-in",
                    type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                    amount = "1000",
                    dest = accountA,
                ),
                tx(
                    id = "purchase",
                    type = FinancialTransactionType.EXPENSE,
                    amount = "500",
                    source = accountA,
                ),
                tx(
                    id = "self-out",
                    type = FinancialTransactionType.SELF_TRANSFER,
                    amount = "200",
                    source = accountA,
                    dest = accountB,
                ),
            ),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountA),
            ownedAccountLast4s = setOf("3001"),
        )
        assertEquals(Money.of("1000.00", Currency.SAR), summary.inflow.total)
        assertEquals(Money.of("700.00", Currency.SAR), summary.outflow.total)
        assertEquals(Money.of("200.00", Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(
            SignedMoneyAmount.of(Money.of("300.00", Currency.SAR)),
            summary.cashPosition().remaining,
        )
        assertEquals(
            SignedMoneyAmount.of(Money.of("500.00", Currency.SAR)),
            summary.externalMovement().remaining,
        )
    }

    @Test
    fun accountOutflow_includesAllListedExpenseCategories() {
        val accountId = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:7271"
        val otherAccount = "account:bank_aljazira:3002"
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(
                tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountId),
                tx("card-pay", FinancialTransactionType.CREDIT_CARD_PAYMENT, "50", source = accountId, dest = cardId),
                tx("cash", FinancialTransactionType.CASH_WITHDRAWAL, "30", source = accountId),
                tx("bill", FinancialTransactionType.BILL_PAYMENT, "40", source = accountId),
                tx("pos", FinancialTransactionType.EXPENSE, "60", source = accountId),
                tx("fee", FinancialTransactionType.FEE, "10", source = accountId),
                tx(
                    id = "self",
                    type = FinancialTransactionType.SELF_TRANSFER,
                    amount = "200",
                    source = accountId,
                    dest = otherAccount,
                ),
            ),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountId),
            ownedAccountLast4s = setOf("3001"),
        )
        assertEquals(Money.of("100.00", Currency.SAR), summary.outflow.externalTransfersOut)
        assertEquals(Money.of("50.00", Currency.SAR), summary.outflow.creditCardPayments)
        assertEquals(Money.of("30.00", Currency.SAR), summary.outflow.cashWithdrawals)
        assertEquals(Money.of("40.00", Currency.SAR), summary.outflow.billPayments)
        assertEquals(Money.of("60.00", Currency.SAR), summary.outflow.posPurchases)
        assertEquals(Money.of("10.00", Currency.SAR), summary.outflow.fees)
        assertEquals(Money.of("200.00", Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(
            Money.of("490.00", Currency.SAR),
            summary.outflow.total,
        )
    }

    @Test
    fun expenseResolvedFromReview_countsUsingLinkedAccountAndSmsFamily() {
        val owned = "account:bank_aljazira:3001"
        val cardPay = tx(
            id = "card-pay-expense",
            type = FinancialTransactionType.EXPENSE,
            amount = "802.62",
            source = null,
            linked = listOf("evt-card-pay"),
        )
        val cash = tx(
            id = "cash-expense",
            type = FinancialTransactionType.EXPENSE,
            amount = "500.00",
            source = null,
            linked = listOf("evt-cash"),
        )
        val bill = tx(
            id = "bill-expense",
            type = FinancialTransactionType.EXPENSE,
            amount = "210.00",
            source = null,
            linked = listOf("evt-bill"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-card-pay",
                family = MessageFamily.CARD_PAYMENT,
                sourceLast4 = "3001",
                cardLast4 = "7271",
                rawBody = "سداد بطاقة ائتمانية\nمن حساب: 3001\nبطاقة: 7271",
            ),
            parsedRecord(
                id = "evt-cash",
                family = MessageFamily.WITHDRAWAL,
                sourceLast4 = "3001",
                rawBody = "سحب نقدي\nمن حساب: 3001",
            ),
            parsedRecord(
                id = "evt-bill",
                family = MessageFamily.BILL_PAYMENT,
                sourceLast4 = "3001",
                rawBody = "سداد فاتورة\nالمفوتر: TEST",
            ),
        )
        val rawSmsById = parsedRecords.associate { record ->
            record.event.rawSmsId to RawSms(
                id = record.event.rawSmsId,
                sender = "AlJazira",
                body = record.event.counterparty.orEmpty(),
                receivedAt = Instant.parse("2026-08-10T12:00:00Z"),
                deviceMessageId = record.event.id,
                bodyHash = record.event.id,
            )
        }
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(cardPay, cash, bill),
            parsedRecords = parsedRecords,
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            rawSmsById = rawSmsById,
        )
        assertEquals(Money.of("802.62", Currency.SAR), summary.outflow.creditCardPayments)
        assertEquals(Money.of("500.00", Currency.SAR), summary.outflow.cashWithdrawals)
        assertEquals(Money.of("210.00", Currency.SAR), summary.outflow.billPayments)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.posPurchases)
    }

    @Test
    fun summarize_filtersToOwnedAccountsOnly() {
        val owned = "account:bank_aljazira:3001"
        val other = "account:bank_aljazira:3002"
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(
                tx("owned-pos", FinancialTransactionType.EXPENSE, "90", source = owned),
                tx("other-pos", FinancialTransactionType.EXPENSE, "40", source = other),
            ),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.of("90.00", Currency.SAR), summary.outflow.posPurchases)
    }

    @Test
    fun madaPosWithLegacyCardSource_countsWhenSmsLinksOwnedAccount() {
        val owned = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:2210"
        val pos = tx(
            id = "pos-mada",
            type = FinancialTransactionType.EXPENSE,
            amount = "120.00",
            source = cardId,
            linked = listOf("evt-pos"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-pos",
                family = MessageFamily.PURCHASE,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                rawBody = "شراء من نقاط البيع\nبطاقة مدى: 2210\nخصمت من حساب: 3001",
            ),
        )
        val rawSmsById = parsedRecords.associate { record ->
            record.event.rawSmsId to RawSms(
                id = record.event.rawSmsId,
                sender = "AlJazira",
                body = record.event.counterparty.orEmpty(),
                receivedAt = Instant.parse("2026-08-01T11:05:00Z"),
                deviceMessageId = record.event.id,
                bodyHash = record.event.id,
            )
        }
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(pos),
            parsedRecords = parsedRecords,
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            rawSmsById = rawSmsById,
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.of("120.00", Currency.SAR), summary.outflow.posPurchases)
    }

    @Test
    fun orphanCashWithdrawal_countedAtFleetScope_only() {
        val owned = "account:bank_aljazira:3001"
        val cashWithdrawal = tx(
            id = "orphan-cash",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "2200.00",
            source = null,
            linked = emptyList(),
        )
        val fleetSummary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(cashWithdrawal),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.Fleet,
        )
        val accountSummary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(cashWithdrawal),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        assertEquals(Money.of("2200.00", Currency.SAR), fleetSummary.outflow.cashWithdrawals)
        assertEquals(Money.zero(Currency.SAR), accountSummary.outflow.cashWithdrawals)
    }

    @Test
    fun selfTransfersTrackedSeparatelyWithoutAffectingNet() {
        val ownedA = "account:bank_aljazira:3001"
        val ownedB = "account:bank_aljazira:3002"
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(
                tx(
                    id = "self",
                    type = FinancialTransactionType.SELF_TRANSFER,
                    amount = "500",
                    source = ownedA,
                    dest = ownedB,
                ),
            ),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(ownedA, ownedB),
            ownedAccountLast4s = setOf("3001", "3002"),
        )
        assertEquals(Money.of("500.00", Currency.SAR), summary.inflow.selfTransfersIn)
        assertEquals(Money.of("500.00", Currency.SAR), summary.outflow.selfTransfersOut)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summary.netMovement)
    }

    @Test
    fun spendingSplit_totalSpending_matchesCurrentAccountOutflow() {
        val accountId = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:7271"
        val split = CurrentAccountSummaryCalculator.spendingSplit(
            transactions = listOf(
                tx("pos", FinancialTransactionType.EXPENSE, "90", source = accountId),
                tx("xfer-out", FinancialTransactionType.EXTERNAL_TRANSFER_OUT, "100", source = accountId),
                tx("card-pay", FinancialTransactionType.CREDIT_CARD_PAYMENT, "50", source = accountId, dest = cardId),
                tx("card", FinancialTransactionType.EXPENSE, "75", source = cardId),
            ),
            parsedRecords = emptyList(),
        )
        assertEquals(Money.of("240.00", Currency.SAR), split.totalSpending)
        assertEquals(SignedMoneyAmount.of(Money.of("75.00", Currency.SAR)), split.creditCardPurchases)
    }

    @Test
    fun feeWithFinancingInstallmentFamily_countsAsLoanRepayment() {
        val owned = "account:bank_aljazira:3001"
        val feeLoan = tx(
            id = "fee-loan",
            type = FinancialTransactionType.FEE,
            amount = "3036.11",
            source = owned,
            linked = listOf("evt-fee-loan"),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(feeLoan),
            parsedRecords = listOf(
                parsedRecord(
                    id = "evt-fee-loan",
                    family = MessageFamily.FINANCING_INSTALLMENT,
                    sourceLast4 = "3001",
                    rawBody = "خصم: قسط تمويل\nمن: 3001\nلـ: تمويل شخصي",
                    loanType = com.baraa.masroof.domain.model.LoanType.PERSONAL,
                ),
            ),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
            rawSmsById = mapOf(
                "sms-evt-fee-loan" to RawSms(
                    id = "sms-evt-fee-loan",
                    sender = "AlJazira",
                    body = "خصم: قسط تمويل\nمن: 3001\nلـ: تمويل شخصي",
                    receivedAt = Instant.parse("2026-08-27T01:10:00Z"),
                    deviceMessageId = "evt-fee-loan",
                    bodyHash = "evt-fee-loan",
                ),
            ),
        )
        assertEquals(Money.of("3036.11", Currency.SAR), summary.outflow.loanRepayments)
        assertEquals(Money.zero(Currency.SAR), summary.outflow.fees)
        assertEquals(Money.of("3036.11", Currency.SAR), summary.outflow.coreTotal)
    }

    @Test
    fun accountRefundAndPurchase_sameSalaryPeriod_netsCashWithoutIncomeOrDoubleCount() {
        val zone = ZoneId.of("Asia/Riyadh")
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-09-10"))
        val purchaseAt = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(3_600)
        val refundAt = purchaseAt.plusSeconds(86_400)
        val accountId = "account:BANK_ALJAZIRA:3001"
        val creditCardId = "card:BANK_ALJAZIRA:7271"
        val purchase = tx(
            id = "purchase",
            type = FinancialTransactionType.EXPENSE,
            amount = "100.00",
            source = accountId,
            occurredAt = purchaseAt,
        )
        val accountRefund = tx(
            id = "account-refund",
            type = FinancialTransactionType.REFUND,
            amount = "100.00",
            dest = accountId,
            occurredAt = refundAt,
        )
        val cardRefund = tx(
            id = "card-refund",
            type = FinancialTransactionType.REFUND,
            amount = "25.00",
            dest = creditCardId,
            occurredAt = refundAt.plusSeconds(3_600),
            linked = listOf("evt-card-refund"),
        )
        val periodTransactions = listOf(purchase, accountRefund, cardRefund).filter {
            inSalaryPeriod(it.occurredAt, period, zone)
        }
        assertEquals(listOf("purchase", "account-refund", "card-refund"), periodTransactions.map { it.id })

        val summary = summarizeOwned(periodTransactions, accountId)
        val split = CurrentAccountSummaryCalculator.spendingSplit(
            transactions = periodTransactions,
            parsedRecords = listOf(
                parsedRecord(
                    id = "evt-card-refund",
                    family = MessageFamily.REFUND,
                    cardLast4 = "7271",
                    cardSmsChannel = CardSmsChannel.CREDIT,
                ),
            ),
            ownedAccountContainerIds = setOf(accountId),
            ownedAccountLast4s = setOf("3001"),
        )
        val grouping = CurrentAccountFlowDetailGrouper.group(
            transactions = periodTransactions,
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountId),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )

        assertEquals(Money.of("100.00", Currency.SAR), summary.outflow.posPurchases)
        assertEquals(Money.of("100.00", Currency.SAR), summary.inflow.accountRefunds)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.salary)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.otherIncome)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.externalTransfersIn)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summary.netMovement)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summary.cashPosition().remaining)
        assertEquals(Money.of("100.00", Currency.SAR), split.totalSpending)
        assertEquals(
            SignedMoneyAmount.difference(Money.zero(Currency.SAR), Money.of("25.00", Currency.SAR)),
            split.creditCardPurchases,
        )
        assertEquals(listOf("account-refund"), grouping.income.getValue(FlowIncomeCategory.ACCOUNT_REFUND).map { it.id })
        assertTrue(grouping.income.getValue(FlowIncomeCategory.SALARY).isEmpty())
        assertTrue(grouping.income.getValue(FlowIncomeCategory.OTHER_INCOME).isEmpty())
        assertEquals(
            summary.inflow.accountRefunds,
            grouping.income.getValue(FlowIncomeCategory.ACCOUNT_REFUND)
                .fold(Money.zero(Currency.SAR)) { acc, row -> acc + row.amount },
        )
    }

    @Test
    fun accountRefund_adjacentSalaryPeriod_doesNotChangeCurrentPeriodOrDoubleCount() {
        val zone = ZoneId.of("Asia/Riyadh")
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-09-10"))
        val next = FinancialPeriodPolicy.next(period)
        val accountId = "account:BANK_ALJAZIRA:3001"
        val purchaseAt = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone).plusSeconds(3_600)
        val refundAt = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zone)
        val purchase = tx(
            id = "purchase",
            type = FinancialTransactionType.EXPENSE,
            amount = "100.00",
            source = accountId,
            occurredAt = purchaseAt,
        )
        val refund = tx(
            id = "account-refund",
            type = FinancialTransactionType.REFUND,
            amount = "100.00",
            dest = accountId,
            occurredAt = refundAt,
        )
        assertTrue(inSalaryPeriod(purchase.occurredAt, period, zone))
        assertTrue(!inSalaryPeriod(refund.occurredAt, period, zone))
        assertTrue(inSalaryPeriod(refund.occurredAt, next, zone))

        val current = summarizeOwned(listOf(purchase).filter { inSalaryPeriod(it.occurredAt, period, zone) }, accountId)
        val adjacent = summarizeOwned(listOf(refund).filter { inSalaryPeriod(it.occurredAt, next, zone) }, accountId)

        assertEquals(Money.of("100.00", Currency.SAR), current.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), current.inflow.accountRefunds)
        assertEquals(
            SignedMoneyAmount.difference(Money.zero(Currency.SAR), Money.of("100.00", Currency.SAR)),
            current.cashPosition().remaining,
        )
        assertEquals(Money.zero(Currency.SAR), adjacent.outflow.posPurchases)
        assertEquals(Money.of("100.00", Currency.SAR), adjacent.inflow.accountRefunds)
        assertEquals(Money.zero(Currency.SAR), adjacent.inflow.salary)
        assertEquals(SignedMoneyAmount.of(Money.of("100.00", Currency.SAR)), adjacent.cashPosition().remaining)
        assertEquals(
            SignedMoneyAmount.zero(Currency.SAR),
            current.cashPosition().remaining.plus(adjacent.cashPosition().remaining),
        )
        assertEquals(
            Money.of("100.00", Currency.SAR),
            current.inflow.accountRefunds + adjacent.inflow.accountRefunds,
        )
    }

    @Test
    fun creditCardRefund_doesNotIncreaseAccountCash_andUnknownDestinationStaysOut() {
        val accountId = "account:BANK_ALJAZIRA:3001"
        val creditCardId = "card:BANK_ALJAZIRA:7271"
        val summary = summarizeOwned(
            transactions = listOf(
                tx("pos", FinancialTransactionType.EXPENSE, "100.00", source = accountId),
                tx("card-refund", FinancialTransactionType.REFUND, "25.00", dest = creditCardId),
                tx("unknown-refund", FinancialTransactionType.REFUND, "40.00", dest = null, linked = emptyList()),
            ),
            accountId = accountId,
        )

        assertEquals(Money.of("100.00", Currency.SAR), summary.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.accountRefunds)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.salary)
        assertEquals(Money.zero(Currency.SAR), summary.inflow.otherIncome)
        assertEquals(
            SignedMoneyAmount.difference(Money.zero(Currency.SAR), Money.of("100.00", Currency.SAR)),
            summary.cashPosition().remaining,
        )
    }

    @Test
    fun accountRefund_countedOnceAcrossFleetAccounts() {
        val accountA = "account:BANK_ALJAZIRA:3001"
        val accountB = "account:BANK_ALJAZIRA:3002"
        val transactions = listOf(
            tx("pos", FinancialTransactionType.EXPENSE, "100.00", source = accountA),
            tx("refund", FinancialTransactionType.REFUND, "100.00", dest = accountA),
        )
        val fleet = CurrentAccountSummaryCalculator.summarize(
            transactions = transactions,
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountA, accountB),
            ownedAccountLast4s = setOf("3001", "3002"),
            scopeMode = AccountFlowScopeMode.Fleet,
        )
        val summaryA = summarizeOwned(transactions, accountA)
        val summaryB = summarizeOwned(transactions, accountB)

        assertEquals(Money.of("100.00", Currency.SAR), fleet.inflow.accountRefunds)
        assertEquals(Money.of("100.00", Currency.SAR), summaryA.inflow.accountRefunds)
        assertEquals(Money.zero(Currency.SAR), summaryB.inflow.accountRefunds)
        assertEquals(fleet.inflow.accountRefunds, summaryA.inflow.accountRefunds + summaryB.inflow.accountRefunds)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), fleet.cashPosition().remaining)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summaryA.cashPosition().remaining)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summaryB.cashPosition().remaining)
        assertEquals(Money.of("100.00", Currency.SAR), fleet.outflow.posPurchases)
    }

    @Test
    fun twoBanksSharingAccountSuffix_keepSeparatePosTotals() {
        val aljazira = "account:BANK_ALJAZIRA:3001"
        val other = "account:OTHER_BANK:3001"
        val transactions = listOf(
            tx("alj", FinancialTransactionType.EXPENSE, "100.00", source = aljazira),
            tx("other", FinancialTransactionType.EXPENSE, "250.00", source = other),
        )
        val fleet = CurrentAccountSummaryCalculator.summarize(
            transactions = transactions,
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(aljazira, other),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.Fleet,
        )
        val aljaziraSummary = summarizeOwned(transactions, aljazira)
        val otherSummary = summarizeOwned(transactions, other)

        assertEquals(Money.of("100.00", Currency.SAR), aljaziraSummary.outflow.posPurchases)
        assertEquals(Money.of("250.00", Currency.SAR), otherSummary.outflow.posPurchases)
        assertEquals(Money.of("350.00", Currency.SAR), fleet.outflow.posPurchases)
        assertEquals(
            fleet.outflow.posPurchases,
            aljaziraSummary.outflow.posPurchases + otherSummary.outflow.posPurchases,
        )
    }

    @Test
    fun unqualifiedSuffix_matchesOnlyWhenOneOwnedAccountHasIt() {
        val aljazira = "account:BANK_ALJAZIRA:3001"
        val other = "account:OTHER_BANK:3001"
        val legacy = tx(
            "legacy",
            FinancialTransactionType.EXPENSE,
            "40.00",
            source = "account:3001",
        )
        val unique = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(legacy),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(aljazira),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )
        val ambiguous = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(legacy),
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(aljazira, other),
            ownedAccountLast4s = setOf("3001"),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )

        assertEquals(Money.of("40.00", Currency.SAR), unique.outflow.posPurchases)
        assertEquals(Money.zero(Currency.SAR), ambiguous.outflow.posPurchases)
    }

    @Test
    fun loanRepayment_countsInAccountOutflow() {
        val owned = "account:bank_aljazira:3001"
        val loanTx = tx(
            id = "loan",
            type = FinancialTransactionType.LOAN_REPAYMENT,
            amount = "3036.11",
            source = owned,
            dest = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, com.baraa.masroof.domain.model.LoanType.PERSONAL),
            linked = listOf("evt-loan"),
        )
        val summary = CurrentAccountSummaryCalculator.summarize(
            transactions = listOf(loanTx),
            parsedRecords = listOf(
                parsedRecord(
                    id = "evt-loan",
                    family = MessageFamily.FINANCING_INSTALLMENT,
                    sourceLast4 = "3001",
                ),
            ),
            ownedAccountContainerIds = setOf(owned),
            ownedAccountLast4s = setOf("3001"),
        )
        assertEquals(Money.of("3036.11", Currency.SAR), summary.outflow.loanRepayments)
        assertEquals(Money.of("3036.11", Currency.SAR), summary.outflow.coreTotal)
    }

    private fun summarizeOwned(
        transactions: List<FinancialTransaction>,
        accountId: String,
    ): CurrentAccountSummary =
        CurrentAccountSummaryCalculator.summarize(
            transactions = transactions,
            parsedRecords = emptyList(),
            ownedAccountContainerIds = setOf(accountId),
            ownedAccountLast4s = setOf(accountId.substringAfterLast(':')),
            scopeMode = AccountFlowScopeMode.SingleAccount,
        )

    private fun inSalaryPeriod(
        instant: Instant,
        period: FinancialPeriod,
        zone: ZoneId,
    ): Boolean {
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(period.startDate, zone)
        val end = FinancialPeriodPolicy.toExclusiveEndInstant(period.endDateExclusive, zone)
        return !instant.isBefore(start) && instant.isBefore(end)
    }

    private fun tx(
        id: String,
        type: FinancialTransactionType,
        amount: String,
        source: String? = null,
        dest: String? = null,
        linked: List<String> = listOf("evt-$id"),
        occurredAt: Instant = Instant.parse("2026-08-10T12:00:00Z"),
    ): FinancialTransaction =
        FinancialTransaction(
            id = id,
            type = type,
            amount = Money.of(amount, Currency.SAR),
            occurredAt = occurredAt,
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
        sourceLast4: String? = null,
        cardLast4: String? = null,
        rawBody: String? = null,
        loanType: com.baraa.masroof.domain.model.LoanType? = null,
        salaryIncomeWording: Boolean? = null,
        cardSmsChannel: CardSmsChannel? = null,
    ): ParsedEventRecord {
        val event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = family,
            direction = com.baraa.masroof.domain.model.MoneyDirection.INCOMING,
            amount = Money.of("1.00", Currency.SAR),
            purchaseChannel = null,
            sourceAccountRef = sourceLast4?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
            destinationAccountRef = null,
            cardRef = cardLast4?.let { CardReference(Bank.BANK_ALJAZIRA, it) },
            merchant = null,
            counterparty = rawBody,
            occurredAt = Instant.parse("2026-08-10T12:00:00Z"),
            bankNetworkType = null,
            confidence = com.baraa.masroof.domain.model.Confidence(1.0),
            parseStatus = com.baraa.masroof.domain.model.ParseStatus.SUCCESS,
        )
        return ParsedEventRecord(
            event = event,
            details = ParsedEventDetails(
                loanType = loanType,
                salaryIncomeWording = salaryIncomeWording,
                cardSmsChannel = cardSmsChannel,
            ),
        )
    }
}
