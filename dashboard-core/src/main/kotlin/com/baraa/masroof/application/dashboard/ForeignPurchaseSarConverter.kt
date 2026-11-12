package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Estimates SAR impact for foreign-currency purchases using persisted parse-time facts.
 */
object ForeignPurchaseSarConverter {
    fun foreignToSar(
        foreignAmount: Money,
        exchangeRate: BigDecimal,
        internationalFee: Money? = null,
        targetCurrency: Currency = Currency.SAR,
    ): Money? {
        if (!foreignAmount.currency.convertsToSar() || targetCurrency != Currency.SAR) return null
        val converted = foreignAmount.amount
            .multiply(exchangeRate)
            .setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        // The fee is added in SAR: a fee in the purchase currency is converted at the same rate,
        // and a fee in any other currency cannot be converted here, so it is left out.
        val fee = when (internationalFee?.currency) {
            Currency.SAR -> internationalFee.amount
            foreignAmount.currency -> internationalFee.amount.multiply(exchangeRate).setScale(Money.SCALE, RoundingMode.HALF_EVEN)
            else -> BigDecimal.ZERO.setScale(Money.SCALE)
        }
        val total = converted.add(fee).setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        return Money.of(total, Currency.SAR)
    }
}
