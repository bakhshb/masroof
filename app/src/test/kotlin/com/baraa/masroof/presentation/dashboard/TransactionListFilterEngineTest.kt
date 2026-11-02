package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionListFilterEngineTest {
    @Test
    fun noFilter_returnsAllTransactions() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", merchant = "Keeta", card = "7271"),
            preview(type = FinancialTransactionType.INCOME, amount = "5000"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState()).result
        assertEquals(2, result.transactions.size)
        assertNull(result.totalAmount) // income and spending do not share one total
    }

    @Test
    fun transactionIdFilter_limitsResults() {
        val txs = listOf(
            preview(id = "keep", type = FinancialTransactionType.EXPENSE, amount = "100", card = "7271"),
            preview(id = "drop", type = FinancialTransactionType.EXPENSE, amount = "50", card = "7271"),
        )

        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(transactionIds = setOf("keep"))).result

        assertEquals(listOf("keep"), result.transactions.map { it.id })
    }

    @Test
    fun typeFilter_limitsResults() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", card = "7271"),
            preview(type = FinancialTransactionType.INCOME, amount = "5000"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(types = setOf(FinancialTransactionType.EXPENSE))).result
        assertEquals(1, result.transactions.size)
        assertEquals(FinancialTransactionType.EXPENSE, result.transactions.single().type)
        assertEquals(Money.of("100.00", Currency.SAR), result.totalAmount)
    }

    @Test
    fun multipleTypes_useOrWithinTypes() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100"),
            preview(type = FinancialTransactionType.REFUND, amount = "20"),
            preview(type = FinancialTransactionType.INCOME, amount = "5000"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(
                types = setOf(
                    FinancialTransactionType.EXPENSE,
                    FinancialTransactionType.REFUND,
                ),
            )).result
        assertEquals(2, result.transactions.size)
        assertNull(result.totalAmount) // refunds and purchases do not share one total
    }

    @Test
    fun cardFilter_limitsResults() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", card = "7271"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50", card = "3478"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(cardLast4s = setOf("7271"))).result
        assertEquals(1, result.transactions.size)
        assertEquals("7271", result.transactions.single().cardLast4)
    }

    @Test
    fun multipleCards_useOrWithinCards() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", card = "7271"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50", card = "3478"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "30", card = "8332"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(cardLast4s = setOf("7271", "3478"))).result
        assertEquals(2, result.transactions.size)
        assertEquals(Money.of("150.00", Currency.SAR), result.totalAmount)
    }

    @Test
    fun typeAndCardFilter_combineWithAnd() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", card = "7271"),
            preview(type = FinancialTransactionType.REFUND, amount = "20", card = "7271"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50", card = "3478"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(
                types = setOf(FinancialTransactionType.EXPENSE),
                cardLast4s = setOf("7271"),
            )).result
        assertEquals(1, result.transactions.size)
        assertEquals(Money.of("100.00", Currency.SAR), result.totalAmount)
    }

    @Test
    fun multipleTypesCardsAndSearch_combineWithAnd() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", merchant = "Tamara", card = "7271"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50", merchant = "TABBY", card = "7271"),
            preview(type = FinancialTransactionType.REFUND, amount = "20", merchant = "Tamara", card = "7271"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", merchant = "Tamara", card = "3478"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(
                searchQuery = "tamara",
                types = setOf(FinancialTransactionType.EXPENSE, FinancialTransactionType.REFUND),
                cardLast4s = setOf("7271"),
            )).result
        assertEquals(2, result.transactions.size)
        assertNull(result.totalAmount) // refunds and purchases do not share one total
    }

    @Test
    fun searchByMerchantName_matchesCounterpartyToo() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", merchant = "Keeta"),
            preview(type = FinancialTransactionType.EXTERNAL_TRANSFER_IN, amount = "500", counterparty = "نجاه"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(searchQuery = "keeta")).result
        assertEquals(1, result.transactions.size)
        assertEquals("Keeta", result.transactions.single().title)
    }

    @Test
    fun searchByAmount_partialAndExact() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "438.50", merchant = "SEC"),
        )
        val partial = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(searchQuery = "438")).result
        assertEquals(1, partial.transactions.size)

        val exact = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(searchQuery = "438.5")).result
        assertEquals(1, exact.transactions.size)

        val miss = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(searchQuery = "439")).result
        assertEquals(0, miss.transactions.size)
    }

    @Test
    fun availableTypes_followsDisplayOrder() {
        val txs = listOf(
            preview(type = FinancialTransactionType.INCOME, amount = "1"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "2"),
            preview(type = FinancialTransactionType.FEE, amount = "3"),
        )
        assertEquals(
            listOf(
                FinancialTransactionType.EXPENSE,
                FinancialTransactionType.INCOME,
                FinancialTransactionType.FEE,
            ),
            TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState()).availableTypes,
        )
    }

    @Test
    fun typeFilter_matchesEffectiveLoanRepaymentLabel() {
        val txs = listOf(
            preview(
                type = FinancialTransactionType.FEE,
                effectiveType = FinancialTransactionType.LOAN_REPAYMENT,
                amount = "3036.11",
            ),
            preview(type = FinancialTransactionType.FEE, amount = "2"),
        )
        val filtered = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(types = setOf(FinancialTransactionType.LOAN_REPAYMENT))).result
        assertEquals(1, filtered.transactions.size)
        assertEquals("3036.11", filtered.transactions.single().amount.amount.toPlainString())
    }

    @Test
    fun nonPrimaryCurrency_suppressesTotal() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", currency = Currency.SAR),
            preview(type = FinancialTransactionType.EXPENSE, amount = "23", currency = Currency.USD),
        )
        assertNull(TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState()).result.totalAmount)
    }

    @Test
    fun accountFilter_limitsResults() {
        val accountId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "1234567890123001",
        )
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "100", sourceAccountId = accountId),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(accountContainerIds = setOf(accountId))).result
        assertEquals(1, result.transactions.size)
        assertEquals(Money.of("100.00", Currency.SAR), result.totalAmount)
    }

    @Test
    fun availableCardLast4s_limitsToOwnedCards() {
        val cardId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.cardId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "8219",
        )
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "10", card = "8219", sourceAccountId = cardId),
            preview(type = FinancialTransactionType.EXPENSE, amount = "20", card = "9999"),
        )

        val available = TransactionListPresentationMapper.project(transactions = txs, ownedCardKeys = setOf(CardOwnershipKey.of(com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA, "8219")), filter = TransactionListFilterState()).availableCards

        assertEquals(listOf("8219"), available)
    }

    @Test
    fun availableCardLast4s_returnsEmptyWhenNoOwnedCards() {
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "10", card = "8219"),
        )

        assertEquals(
            emptyList<String>(),
            TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState()).availableCards,
        )
    }

    @Test
    fun availableAccountContainerIds_limitsToOwnedAccountsAndInvolvement() {
        val ownedId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "3001",
        )
        val otherId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "8842",
        )
        val txs = listOf(
            preview(id = "cash", type = FinancialTransactionType.CASH_WITHDRAWAL, amount = "100"),
            preview(type = FinancialTransactionType.EXPENSE, amount = "50", sourceAccountId = otherId),
        )

        val available = TransactionListPresentationMapper.project(transactions = txs, ownedAccountContainerIds = setOf(ownedId), accountInvolvementByTransactionId = mapOf("cash" to setOf(ownedId)), filter = TransactionListFilterState()).availableAccounts

        assertEquals(listOf(ownedId), available)
    }

    @Test
    fun accountFilter_matchesSmsResolvedInvolvement() {
        val accountId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "3001",
        )
        val txs = listOf(
            preview(id = "cash", type = FinancialTransactionType.CASH_WITHDRAWAL, amount = "100"),
        )
        val result = TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState(accountContainerIds = setOf(accountId)), accountInvolvementByTransactionId = mapOf("cash" to setOf(accountId))).result

        assertEquals(listOf("cash"), result.transactions.map { it.id })
    }

    @Test
    fun availableAccountContainerIds_listsDistinctSorted() {
        val accountId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "3001",
        )
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "1", sourceAccountId = accountId),
            preview(type = FinancialTransactionType.INCOME, amount = "2", destinationAccountId = accountId),
        )
        assertEquals(
            listOf(accountId),
            TransactionListPresentationMapper.project(transactions = txs, ownedAccountContainerIds = setOf(accountId), filter = TransactionListFilterState()).availableAccounts,
        )
    }

    @Test
    fun availableAccountContainerIds_returnsEmptyWhenNoOwnedAccounts() {
        val accountId = com.baraa.masroof.domain.ids.FinancialContainerIdFactory.accountId(
            com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA,
            "3001",
        )
        val txs = listOf(
            preview(type = FinancialTransactionType.EXPENSE, amount = "1", sourceAccountId = accountId),
        )
        assertEquals(
            emptyList<String>(),
            TransactionListPresentationMapper.project(transactions = txs, filter = TransactionListFilterState()).availableAccounts,
        )
    }

    private fun preview(
        id: String = "tx-${System.nanoTime()}",
        type: FinancialTransactionType,
        effectiveType: FinancialTransactionType = type,
        amount: String,
        merchant: String? = null,
        counterparty: String? = null,
        card: String? = null,
        currency: Currency = Currency.SAR,
        sourceAccountId: String? = null,
        destinationAccountId: String? = null,
    ): TransactionPreviewUi {
        val money = Money.of(amount, currency)
        val title = merchant ?: counterparty
        val searchText = listOfNotNull(merchant, counterparty).joinToString(" ").lowercase()
        return TransactionPreviewUi(
            id = id,
            title = title,
            amount = money,
            localDate = java.time.LocalDate.of(2026, 8, 1),
            amountLabel = money.amount.toPlainString(),
            dateLabel = "1 أغسطس",
            type = type,
            typeLabelResHint = effectiveType,
            direction = TransactionTypePresentation.direction(effectiveType),
            cardLast4 = card,
            sourceContainerId = sourceAccountId,
            destinationContainerId = destinationAccountId,
            searchText = searchText,
        )
    }
}
