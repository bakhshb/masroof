package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LoanRepaymentAttributionTest {
    @Test
    fun loanContainerId_prefersLoanRepaymentDestination() {
        val loanId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        val tx = FinancialTransaction(
            id = "tx-loan",
            type = FinancialTransactionType.LOAN_REPAYMENT,
            amount = Money.of("100.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = loanId,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )

        assertEquals(loanId, LoanRepaymentAttribution.loanContainerId(tx, emptyMap()))
    }

    @Test
    fun isLoanRepayment_trueForFeeWithFinancingSms() {
        val parsedRecordsById = mapOf(
            "evt-loan" to financingRecord(counterparty = "تمويل شخصي"),
        )
        val tx = FinancialTransaction(
            id = "fee-loan",
            type = FinancialTransactionType.FEE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )

        assertTrue(LoanRepaymentAttribution.isLoanRepayment(tx, parsedRecordsById))
    }

    @Test
    fun loanContainerId_resolvesFeeFromFinancingSms() {
        val parsedRecordsById = mapOf(
            "evt-loan" to financingRecord(counterparty = "تمويل شخصي"),
        )
        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf("sms-loan")),
            type = FinancialTransactionType.FEE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )

        val loanId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        assertEquals(loanId, LoanRepaymentAttribution.loanContainerId(tx, parsedRecordsById))
        assertTrue(
            LoanRepaymentAttribution.matchesLoan(
                tx = tx,
                parsedRecordsById = parsedRecordsById,
                bank = Bank.BANK_ALJAZIRA,
                loanType = LoanType.PERSONAL,
            ),
        )
    }

    @Test
    fun isLoanRepayment_falseAfterReclassifyToExpense() {
        val parsedRecordsById = mapOf(
            "evt-loan" to financingRecord(counterparty = "تمويل شخصي"),
        )
        val tx = FinancialTransaction(
            id = "expense-loan",
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )

        assertFalse(LoanRepaymentAttribution.isLoanRepayment(tx, parsedRecordsById))
        assertNull(LoanRepaymentAttribution.loanContainerId(tx, parsedRecordsById))
    }

    @Test
    fun buildInvolvementIndex_mapsFeeFinancingInstallments() {
        val parsedRecords = listOf(financingRecord(counterparty = "تمويل شخصي"))
        val tx = FinancialTransaction(
            id = "fee-loan",
            type = FinancialTransactionType.FEE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = "تمويل شخصي",
            categoryId = null,
            linkedParsedEventIds = listOf("evt-loan"),
        )

        val index = LoanRepaymentAttribution.buildInvolvementIndex(
            transactions = listOf(tx),
            parsedRecords = parsedRecords,
        )

        val loanId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        assertEquals(setOf(loanId), index["fee-loan"])
    }

    private fun financingRecord(counterparty: String): ParsedEventRecord =
        ParsedEventRecord(
            event = ParsedEvent(
                id = "evt-loan",
                rawSmsId = "sms-loan",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.FINANCING_INSTALLMENT,
                direction = MoneyDirection.OUTGOING,
                amount = Money.of("3036.11", Currency.SAR),
                purchaseChannel = null,
                cardRef = null,
                sourceAccountRef = com.baraa.masroof.domain.model.AccountReference(
                    Bank.BANK_ALJAZIRA,
                    "3001",
                ),
                destinationAccountRef = null,
                merchant = null,
                counterparty = counterparty,
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
        )
}
