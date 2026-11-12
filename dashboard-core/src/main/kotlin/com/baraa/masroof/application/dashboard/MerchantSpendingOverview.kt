package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/**
 * Stable, conservative merchant grouping key. It intentionally does not infer aliases,
 * strip identifiers, or rewrite stored merchant labels.
 */
object MerchantNameNormalizer {
    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC)
            .trim()
            .replace(WHITESPACE, " ")
            .replace(SPACING_AROUND_SAFE_PUNCTUATION, "$1")
            .lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("""\s+""")
    private val SPACING_AROUND_SAFE_PUNCTUATION = Regex("""\s*([,،._-])\s*""")
}

data class MerchantSpendingRow(
    val merchantKey: String,
    val displayName: String,
    val totalSpent: SignedMoneyAmount,
    /** Purchases only; refunds lower [totalSpent] without contributing to this count. */
    val purchaseTransactionCount: Int,
    /** Purchases and merchant-attributed refunds, for authoritative drill-down. */
    val transactionIds: Set<String>,
)

data class MerchantSpendingOverview(
    val merchants: List<MerchantSpendingRow>,
) {
    val hasContent: Boolean
        get() = merchants.isNotEmpty()

    companion object {
        fun empty(): MerchantSpendingOverview = MerchantSpendingOverview(emptyList())
    }
}

object MerchantSpendingOverviewBuilder {
    const val MINIMUM_PURCHASE_TRANSACTION_COUNT = 2

    fun build(
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
    ): MerchantSpendingOverview {
        val groups = linkedMapOf<String, MutableMerchantGroup>()
        transactions.forEach { transaction ->
            val merchant = transaction.merchant?.takeIf { it.isNotBlank() } ?: return@forEach
            if (transaction.type != FinancialTransactionType.EXPENSE &&
                transaction.type != FinancialTransactionType.REFUND
            ) {
                return@forEach
            }
            val amount = TransactionAmountResolver.effectiveAmount(
                tx = transaction,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            ) ?: return@forEach
            val key = MerchantNameNormalizer.key(merchant)
            if (key.isEmpty()) return@forEach
            val group = groups.getOrPut(key) { MutableMerchantGroup(key, primaryCurrency) }
            group.add(
                transactionId = transaction.id,
                displayName = merchant.trim(),
                amount = amount,
                isPurchase = transaction.type == FinancialTransactionType.EXPENSE,
            )
        }

        return MerchantSpendingOverview(
            merchants = groups.values
                .asSequence()
                .filter { it.purchaseCount >= MINIMUM_PURCHASE_TRANSACTION_COUNT }
                .map { it.toRow() }
                .sortedWith(
                    compareByDescending<MerchantSpendingRow> { it.totalSpent.amount }
                        .thenBy { it.displayName.lowercase(Locale.ROOT) }
                        .thenBy { it.merchantKey },
                )
                .toList(),
        )
    }

    private class MutableMerchantGroup(
        private val key: String,
        private val currency: Currency,
    ) {
        private var total = BigDecimal.ZERO.setScale(Money.SCALE)
        var purchaseCount = 0
            private set
        private val transactionIds = linkedSetOf<String>()
        private val labels = linkedMapOf<String, Int>()

        fun add(transactionId: String, displayName: String, amount: Money, isPurchase: Boolean) {
            require(amount.currency == currency)
            total = total.add(if (isPurchase) amount.amount else amount.amount.negate())
                .setScale(Money.SCALE, RoundingMode.HALF_EVEN)
            if (isPurchase) purchaseCount++
            transactionIds += transactionId
            labels[displayName] = (labels[displayName] ?: 0) + 1
        }

        fun toRow(): MerchantSpendingRow =
            MerchantSpendingRow(
                merchantKey = key,
                displayName = labels.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key,
                totalSpent = SignedMoneyAmount(total, currency),
                purchaseTransactionCount = purchaseCount,
                transactionIds = transactionIds,
            )
    }
}
