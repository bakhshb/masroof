package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class LoanRepaymentDisplayFactsTest {
    @Test
    fun resolve_feeWithFinancingSms_returnsLoanRepayment() {
        val parsedRecords = listOf(financingRecord())
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

        assertEquals(
            FinancialTransactionType.LOAN_REPAYMENT,
            effectiveType(tx, parsedRecords),
        )
    }

    @Test
    fun resolve_plainFee_returnsStoredType() {
        val tx = FinancialTransaction(
            id = "fee",
            type = FinancialTransactionType.FEE,
            amount = Money.of("2.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )

        assertEquals(FinancialTransactionType.FEE, effectiveType(tx, emptyList()))
    }

    @Test
    fun resolve_reclassifiedExpense_returnsStoredType() {
        val parsedRecords = listOf(financingRecord())
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

        assertEquals(FinancialTransactionType.EXPENSE, effectiveType(tx, parsedRecords))
    }

    private fun effectiveType(
        transaction: FinancialTransaction,
        parsedRecords: List<ParsedEventRecord>,
    ): FinancialTransactionType =
        DashboardTransactionFactsBuilder.build(
            transactions = listOf(transaction),
            cardInvolvement = emptyMap(),
            loanInvolvement = LoanRepaymentAttribution.buildInvolvementIndex(listOf(transaction), parsedRecords),
        ).getValue(transaction.id).effectiveType

    private fun financingRecord(): ParsedEventRecord =
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
                sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
                destinationAccountRef = null,
                merchant = null,
                counterparty = "تمويل شخصي",
                occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
                bankNetworkType = null,
                confidence = Confidence(1.0),
                parseStatus = ParseStatus.SUCCESS,
            ),
            details = ParsedEventDetails(loanType = LoanType.PERSONAL),
        )
}
