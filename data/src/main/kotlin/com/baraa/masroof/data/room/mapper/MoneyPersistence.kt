package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal

/**
 * Lossless Money ↔ column pair mapping. Never uses Double/Float.
 */
object MoneyPersistence {
    fun toColumns(money: Money?): Pair<String?, String?> {
        if (money == null) return null to null
        return money.amount.toPlainString() to money.currency.name
    }

    fun fromColumns(decimal: String?, currencyName: String?): Money? {
        if (decimal == null && currencyName == null) return null
        require(decimal != null && currencyName != null) {
            "Incomplete money columns: decimal=$decimal currency=$currencyName"
        }
        val currency = runCatching { Currency.valueOf(currencyName) }.getOrElse {
            error("Unrecognized currency id '$currencyName'")
        }
        return Money.of(BigDecimal(decimal), currency)
    }
}
