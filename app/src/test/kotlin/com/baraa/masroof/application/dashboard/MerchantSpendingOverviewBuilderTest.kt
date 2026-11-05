package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantSpendingOverviewBuilderTest {
    @Test
    fun onePurchase_doesNotQualify() {
        val overview = build(purchases("Cafe", 1))

        assertFalse(overview.hasContent)
    }

    @Test
    fun twoPurchases_qualifyWithExactTransactionIds() {
        val transactions = purchases("Cafe", 2)

        val row = build(transactions).merchants.single()

        assertEquals(2, row.purchaseTransactionCount)
        assertEquals(transactions.map { it.id }.toSet(), row.transactionIds)
        assertEquals(Money.of("20.00", Currency.SAR).amount, row.totalSpent.amount)
    }

    @Test
    fun ranksByTotalSpendingDescending() {
        val overview = build(
            purchases("Lower total", 2, amount = "10") +
                purchases("Higher total", 2, amount = "20"),
        )

        assertEquals(listOf("Higher total", "Lower total"), overview.merchants.map { it.displayName })
    }

    @Test
    fun normalizesOnlySafeTextualDifferencesAndPreservesFrequentDisplayLabel() {
        val transactions = listOf(
            *purchases(" Coffee, Shop ", 1).toTypedArray(),
            *purchases("coffee , shop", 1).toTypedArray(),
        )

        val row = build(transactions).merchants.single()

        assertEquals("coffee,shop", row.merchantKey)
        assertEquals("Coffee, Shop", row.displayName)
    }

    @Test
    fun refund_reducesSpendWithoutIncreasingPurchaseCount() {
        val purchases = purchases("Cafe", 2, amount = "10")
        val refund = tx(
            id = "refund",
            merchant = "CAFE",
            type = FinancialTransactionType.REFUND,
            amount = "12",
        )

        val row = build(purchases + refund).merchants.single()

        assertEquals(2, row.purchaseTransactionCount)
        assertEquals(Money.of("8.00", Currency.SAR).amount, row.totalSpent.amount)
        assertEquals((purchases.map { it.id } + "refund").toSet(), row.transactionIds)
    }

    @Test
    fun excludesNonMerchantAndNonPurchaseTypes() {
        val transactions = purchases("Cafe", 2) + listOf(
            tx("transfer", merchant = "Cafe", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT, amount = "100"),
            tx("loan", merchant = "Cafe", type = FinancialTransactionType.LOAN_REPAYMENT, amount = "100"),
            tx("payment", merchant = "Cafe", type = FinancialTransactionType.CREDIT_CARD_PAYMENT, amount = "100"),
            tx("self", merchant = "Cafe", type = FinancialTransactionType.SELF_TRANSFER, amount = "100"),
            tx("counterparty", merchant = null, type = FinancialTransactionType.EXPENSE, amount = "100"),
        )

        val row = build(transactions).merchants.single()

        assertEquals(2, row.purchaseTransactionCount)
        assertEquals(Money.of("20.00", Currency.SAR).amount, row.totalSpent.amount)
        assertTrue(row.transactionIds.none { it in setOf("transfer", "loan", "payment", "self", "counterparty") })
    }

    @Test
    fun usesSarEquivalentForForeignMerchantPurchase() {
        val transactions = purchases("Cafe", 1) + tx(
            id = "usd",
            merchant = "Cafe",
            type = FinancialTransactionType.EXPENSE,
            amount = "10",
            currency = Currency.USD,
        )

        val row = MerchantSpendingOverviewBuilder.build(
            transactions = transactions,
            primaryCurrency = Currency.SAR,
            sarEquivalents = mapOf("usd" to Money.of("37.50", Currency.SAR)),
        ).merchants.single()

        assertEquals(Money.of("47.50", Currency.SAR).amount, row.totalSpent.amount)
    }

    private fun build(transactions: List<FinancialTransaction>): MerchantSpendingOverview =
        MerchantSpendingOverviewBuilder.build(transactions, Currency.SAR, emptyMap())

    private fun purchases(name: String, count: Int, amount: String = "10"): List<FinancialTransaction> =
        (1..count).map { index ->
            tx("$name-$index", merchant = name, type = FinancialTransactionType.EXPENSE, amount = amount)
        }
}
