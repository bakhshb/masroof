package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AccountTransactionPresentationTest {
    @Test
    fun selfTransferToAccount_showsIncomingDirection() {
        val account3002 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")
        val account3001 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val tx = preview(
            type = FinancialTransactionType.SELF_TRANSFER,
            source = account3001,
            destination = account3002,
            direction = TransactionDirectionUi.NEUTRAL,
        )

        val direction = AccountTransactionPresentation.directionForAccount(
            tx = tx,
            ownedContainerId = account3002,
            ownedLast4s = setOf("3002"),
        )

        assertEquals(TransactionDirectionUi.TRANSFER_IN, direction)
    }

    @Test
    fun selfTransferFromAccount_showsOutgoingDirection() {
        val account3002 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")
        val account3001 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val tx = preview(
            type = FinancialTransactionType.SELF_TRANSFER,
            source = account3002,
            destination = account3001,
            direction = TransactionDirectionUi.NEUTRAL,
        )

        val direction = AccountTransactionPresentation.directionForAccount(
            tx = tx,
            ownedContainerId = account3002,
            ownedLast4s = setOf("3002"),
        )

        assertEquals(TransactionDirectionUi.OUTWARD, direction)
    }

    @Test
    fun forAccount_adjustsDirectionsForFilteredRows() {
        val account3002 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3002")
        val account3001 = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001")
        val incomingSelfTransfer = preview(
            id = "in",
            type = FinancialTransactionType.SELF_TRANSFER,
            source = account3001,
            destination = account3002,
            direction = TransactionDirectionUi.NEUTRAL,
        )
        val outgoingSelfTransfer = preview(
            id = "out",
            type = FinancialTransactionType.SELF_TRANSFER,
            source = account3002,
            destination = account3001,
            direction = TransactionDirectionUi.NEUTRAL,
        )

        val filtered = DashboardSummaryTransactionFilter.forAccount(
            transactions = listOf(incomingSelfTransfer, outgoingSelfTransfer),
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = "3002",
        )

        assertEquals(TransactionDirectionUi.TRANSFER_IN, filtered.first { it.id == "in" }.direction)
        assertEquals(TransactionDirectionUi.OUTWARD, filtered.first { it.id == "out" }.direction)
    }

    private fun preview(
        id: String = "tx",
        type: FinancialTransactionType,
        source: String?,
        destination: String?,
        direction: TransactionDirectionUi,
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
            direction = direction,
            cardLast4 = null,
            sourceContainerId = source,
            destinationContainerId = destination,
            searchText = "sample",
        )
}
