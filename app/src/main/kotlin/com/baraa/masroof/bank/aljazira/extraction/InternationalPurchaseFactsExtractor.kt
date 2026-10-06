package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Facts from international purchase SMS (AlJazira fixture-proven labels).
 */
data class InternationalPurchaseFacts(
    val exchangeRate: BigDecimal,
    val internationalFee: Money?,
)

object InternationalPurchaseFactsExtractor {
    private val EXCHANGE_RATE =
        comparisonRegex("""سعر\s*الصرف\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    private val INTERNATIONAL_FEE =
        comparisonRegex("""رسوم\s*العمليات\s*الدولية\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    fun extract(body: String): InternationalPurchaseFacts? {
        val rate = EXCHANGE_RATE.find(body)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()
            ?: return null
        val feeRaw = INTERNATIONAL_FEE.find(body)?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()
        val fee = feeRaw?.let { Money.of(it.setScale(Money.SCALE, RoundingMode.HALF_EVEN), Currency.SAR) }
        return InternationalPurchaseFacts(exchangeRate = rate, internationalFee = fee)
    }
}
