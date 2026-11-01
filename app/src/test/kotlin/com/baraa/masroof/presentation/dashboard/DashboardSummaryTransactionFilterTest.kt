package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.CardTransactionInvolvementResolver
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DashboardSummaryTransactionFilterTest {
    @Test
    fun forAccount_matchesSourceOrDestinationContainer() {
        val containerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "1234567890123001")
        val otherAccountId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "1234567890128842")
        val transactions = listOf(
            preview(id = "1", source = containerId, destination = null),
            preview(id = "2", source = otherAccountId, destination = null),
            preview(id = "3", source = null, destination = containerId),
        )

        val filtered = DashboardSummaryTransactionFilter.forAccount(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "1234567890123001",
        )

        assertEquals(listOf("1", "3"), filtered.map { it.id })
    }

    @Test
    fun forAccount_matchesContainerLast4WhenMaskedNumberDiffers() {
        val txContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val transactions = listOf(
            preview(id = "1", source = txContainerId, destination = null),
            preview(id = "2", source = null, destination = txContainerId),
        )

        val filtered = DashboardSummaryTransactionFilter.forAccount(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "****3001",
        )

        assertEquals(listOf("1", "2"), filtered.map { it.id })
    }

    @Test
    fun forAccount_matchesSmsResolvedSourceViaInvolvementIndex() {
        val containerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val transactions = listOf(
            preview(id = "cash", source = null, destination = null),
        )
        val involvement = mapOf("cash" to setOf(containerId))

        val filtered = DashboardSummaryTransactionFilter.forAccount(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3001",
            involvementByTransactionId = involvement,
        )

        assertEquals(listOf("cash"), filtered.map { it.id })
    }

    @Test
    fun forCard_matchesCardInvolvementIndex() {
        val cardKey = CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210")
        val transactions = listOf(
            preview(id = "1", cardLast4 = null),
            preview(id = "2", cardLast4 = "3109"),
        )
        val involvement = mapOf("1" to setOf(cardKey))

        val filtered = DashboardSummaryTransactionFilter.forCard(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            cardInvolvementByTransactionId = involvement,
        )

        assertEquals(listOf("1"), filtered.map { it.id })
    }

    @Test
    fun forDebitCard_matchesDebitSpendInvolvementOnly() {
        val cardKey = CardTransactionInvolvementResolver.cardKey(Bank.BANK_ALJAZIRA.id, "2210")
        val transactions = listOf(
            preview(id = "pos", cardLast4 = "2210"),
            preview(id = "bill", cardLast4 = "2210"),
        )
        val involvement = mapOf(
            "pos" to setOf(cardKey),
            "bill" to emptySet(),
        )

        val filtered = DashboardSummaryTransactionFilter.forDebitCard(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            debitSpendInvolvementByTransactionId = involvement,
        )

        assertEquals(listOf("pos"), filtered.map { it.id })
    }

    @Test
    fun forCard_matchesCardLast4() {
        val transactions = listOf(
            preview(id = "1", cardLast4 = "4821"),
            preview(id = "2", cardLast4 = "3109"),
            preview(id = "3", cardLast4 = null),
        )

        val filtered = DashboardSummaryTransactionFilter.forCard(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            last4 = "4821",
        )

        assertEquals(listOf("1"), filtered.map { it.id })
    }

    @Test
    fun forCard_excludesCrossBankContainerWithSameLast4() {
        val otherBankCardId = FinancialContainerIdFactory.cardId(Bank("D360"), "4821")
        val transactions = listOf(
            preview(id = "other-bank", cardLast4 = "4821", source = otherBankCardId),
            preview(id = "legacy", cardLast4 = "4821"),
        )

        val filtered = DashboardSummaryTransactionFilter.forCard(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            last4 = "4821",
        )

        assertEquals(listOf("legacy"), filtered.map { it.id })
    }

    @Test
    fun forLoan_matchesLegacyFeeViaInvolvementIndex() {
        val loanContainerId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        val transactions = listOf(
            preview(
                id = "legacy-fee",
                type = FinancialTransactionType.FEE,
                destination = null,
            ),
            preview(
                id = "real-loan",
                type = FinancialTransactionType.LOAN_REPAYMENT,
                destination = loanContainerId,
            ),
        )

        val filtered = DashboardSummaryTransactionFilter.forLoan(
            transactions = transactions,
            bank = Bank.BANK_ALJAZIRA,
            loanType = LoanType.PERSONAL,
            involvementByTransactionId = mapOf("legacy-fee" to setOf(loanContainerId)),
        )

        assertEquals(listOf("legacy-fee", "real-loan"), filtered.map { it.id })
    }

    private fun preview(
        id: String,
        source: String? = null,
        destination: String? = null,
        cardLast4: String? = null,
        type: FinancialTransactionType = FinancialTransactionType.EXPENSE,
    ): TransactionPreviewUi =
        TransactionPreviewUi(
            id = id,
            title = "Sample",
            amount = Money.of("10.00", Currency.SAR),
            localDate = LocalDate.of(2026, 8, 1),
            amountLabel = "10.00 SAR",
            dateLabel = "1 Aug",
            type = type,
            typeLabelResHint = type,
            direction = TransactionDirectionUi.OUTWARD,
            cardLast4 = cardLast4,
            sourceContainerId = source,
            destinationContainerId = destination,
            searchText = "sample",
        )
}
