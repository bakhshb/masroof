package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class MonthlyFinancialSummaryCalculatorTest {
    private val period = FinancialPeriod(
        startDate = LocalDate.parse("2026-07-27"),
        endDateExclusive = LocalDate.parse("2026-08-27"),
    )

    @Test
    fun expenseOnly_grossAndNet100() {
        val summary = summarize(tx(id = "tx-1", type = FinancialTransactionType.EXPENSE, amount = "100"))
        assertEquals(Money.of("100.00", Currency.SAR), summary.spendingGross)
        assertEquals(Money.zero(Currency.SAR), summary.refunds)
        assertEquals(SignedMoneyAmount.of(Money.of("100.00", Currency.SAR)), summary.spendingNet)
    }

    @Test
    fun expensePlusFee_gross105() {
        val summary = summarize(
            tx(id = "tx-2", type = FinancialTransactionType.EXPENSE, amount = "100"),
            tx(id = "tx-3", type = FinancialTransactionType.FEE, amount = "5"),
        )
        assertEquals(Money.of("105.00", Currency.SAR), summary.spendingGross)
        assertEquals(SignedMoneyAmount.of(Money.of("105.00", Currency.SAR)), summary.spendingNet)
    }

    @Test
    fun expensePlusRefund_net80() {
        val summary = summarize(
            tx(id = "tx-4", type = FinancialTransactionType.EXPENSE, amount = "100"),
            tx(id = "tx-5", type = FinancialTransactionType.REFUND, amount = "20"),
        )
        assertEquals(Money.of("100.00", Currency.SAR), summary.spendingGross)
        assertEquals(Money.of("20.00", Currency.SAR), summary.refunds)
        assertEquals(SignedMoneyAmount.of(Money.of("80.00", Currency.SAR)), summary.spendingNet)
    }

    @Test
    fun refundExceedsExpense_netNegative() {
        val summary = summarize(
            tx(id = "tx-6", type = FinancialTransactionType.REFUND, amount = "120"),
            tx(id = "tx-7", type = FinancialTransactionType.EXPENSE, amount = "100"),
        )
        assertEquals(Money.of("100.00", Currency.SAR), summary.spendingGross)
        assertEquals(Money.of("120.00", Currency.SAR), summary.refunds)
        assertEquals(
            SignedMoneyAmount(BigDecimal("-20.00"), Currency.SAR),
            summary.spendingNet,
        )
    }

    @Test
    fun visaPurchaseAndCardPayment_noDoubleCount() {
        val summary = summarize(
            tx(id = "tx-8", type = FinancialTransactionType.EXPENSE, amount = "100"),
            tx(id = "tx-9", type = FinancialTransactionType.CREDIT_CARD_PAYMENT, amount = "100"),
        )
        assertEquals(Money.of("100.00", Currency.SAR), summary.spendingGross)
        assertEquals(SignedMoneyAmount.of(Money.of("100.00", Currency.SAR)), summary.spendingNet)
        assertEquals(Money.of("100.00", Currency.SAR), summary.creditCardPayments)
        assertEquals(2, summary.transactionCount)
    }

    @Test
    fun selfTransfer_notSpendingOrIncome() {
        val summary = summarize(tx(id = "tx-10", type = FinancialTransactionType.SELF_TRANSFER, amount = "500"))
        assertEquals(Money.zero(Currency.SAR), summary.spendingGross)
        assertEquals(SignedMoneyAmount.zero(Currency.SAR), summary.spendingNet)
        assertEquals(Money.zero(Currency.SAR), summary.income)
        assertEquals(Money.of("500.00", Currency.SAR), summary.selfTransfers)
    }

    @Test
    fun externalTransferOut_notSpending() {
        val summary = summarize(tx(id = "tx-11", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT, amount = "300"))
        assertEquals(Money.zero(Currency.SAR), summary.spendingGross)
        assertEquals(Money.of("300.00", Currency.SAR), summary.externalTransfersOut)
    }

    @Test
    fun externalTransferIn_notIncome() {
        val summary = summarize(tx(id = "tx-12", type = FinancialTransactionType.EXTERNAL_TRANSFER_IN, amount = "300"))
        assertEquals(Money.zero(Currency.SAR), summary.income)
        assertEquals(Money.of("300.00", Currency.SAR), summary.externalTransfersIn)
    }

    @Test
    fun cashWithdrawal_notSpending() {
        val summary = summarize(tx(id = "tx-13", type = FinancialTransactionType.CASH_WITHDRAWAL, amount = "500"))
        assertEquals(Money.zero(Currency.SAR), summary.spendingGross)
        assertEquals(Money.of("500.00", Currency.SAR), summary.cashWithdrawals)
    }

    @Test
    fun income_countsAsIncome() {
        val summary = summarize(tx(id = "tx-14", type = FinancialTransactionType.INCOME, amount = "1000"))
        assertEquals(Money.of("1000.00", Currency.SAR), summary.income)
    }

    @Test
    fun adjustmentAndUnknown_excludedFromTotals_butCountedInTransactionCount() {
        val summary = summarize(
            tx(id = "tx-15", type = FinancialTransactionType.ADJUSTMENT, amount = "40"),
            tx(id = "tx-16", type = FinancialTransactionType.UNKNOWN, amount = "10"),
            tx(id = "tx-17", type = FinancialTransactionType.EXPENSE, amount = "5"),
        )
        assertEquals(Money.of("5.00", Currency.SAR), summary.spendingGross)
        assertEquals(3, summary.transactionCount)
        assertEquals(0, summary.excludedOtherCurrencyCount)
    }

    @Test
    fun transactionCount_isTotalPeriodSize_notOnlyPrimaryTotalsContributors() {
        // Currency enum is SAR-only today; structural count uses full list size so a
        // future non-SAR currency can set excludedOtherCurrencyCount without changing
        // "how many transactions exist" semantics.
        val summary = summarize(
            tx(id = "tx-18", type = FinancialTransactionType.EXPENSE, amount = "10"),
            tx(id = "tx-19", type = FinancialTransactionType.SELF_TRANSFER, amount = "20"),
        )
        assertEquals(2, summary.transactionCount)
        assertEquals(0, summary.excludedOtherCurrencyCount)
    }

    @Test
    fun loanRepayment_notCreditCardPaymentOrSpending() {
        val summary = summarize(tx(id = "tx-20", type = FinancialTransactionType.LOAN_REPAYMENT, amount = "3036.11"))
        assertEquals(Money.zero(Currency.SAR), summary.spendingGross)
        assertEquals(Money.zero(Currency.SAR), summary.creditCardPayments)
        assertEquals(1, summary.transactionCount)
    }

    @Test
    fun feeWithFinancingInstallmentSms_excludedFromSpendingGross() {
        val parsedRecord = com.baraa.masroof.parsing.repository.ParsedEventRecord(
            event = com.baraa.masroof.domain.model.ParsedEvent(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                bank = com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
                messageFamily = com.baraa.masroof.domain.model.MessageFamily.FINANCING_INSTALLMENT,
                direction = com.baraa.masroof.domain.model.MoneyDirection.OUTGOING,
                amount = Money.of("3036.11", Currency.SAR),
                purchaseChannel = null,
                cardRef = null,
                sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                    com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
                    "3001",
                ),
                destinationAccountRef = null,
                merchant = null,
                counterparty = "تمويل شخصي",
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                bankNetworkType = null,
                confidence = com.baraa.masroof.domain.model.Confidence(1.0),
                parseStatus = com.baraa.masroof.domain.model.ParseStatus.SUCCESS,
            ),
            details = com.baraa.masroof.parsing.model.ParsedEventDetails(
                loanType = com.baraa.masroof.domain.model.LoanType.PERSONAL,
            ),
        )
        val feeLoan = FinancialTransaction(
            id = "fee-loan",
            type = FinancialTransactionType.FEE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
                com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
                "3001",
            ),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )
        val summary = MonthlyFinancialSummaryCalculator.summarize(
            period = period,
            transactions = listOf(feeLoan),
            reviewRequiredCount = 0,
            parsedRecords = listOf(parsedRecord),
        )
        assertEquals(Money.zero(Currency.SAR), summary.spendingGross)
    }

    private fun summarize(vararg transactions: FinancialTransaction): MonthlyFinancialSummary =
        MonthlyFinancialSummaryCalculator.summarize(
            period = period,
            transactions = transactions.toList(),
            reviewRequiredCount = 0,
        )
}
