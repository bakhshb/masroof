// Pre-extraction display oracle from main 5e9ec27; test sources only.
package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

internal object FrozenMoneyUiFormatter {
    fun format(money: Money): String = format(money, AppLocale.DEFAULT_TAG)

    fun format(money: Money, languageTag: String): String =
        format(money.amount, money.currency, languageTag)

    fun format(signed: SignedMoneyAmount): String = format(signed, AppLocale.DEFAULT_TAG)

    fun format(signed: SignedMoneyAmount, languageTag: String): String =
        format(signed.amount, signed.currency, languageTag)

    fun format(amount: BigDecimal, currency: Currency): String =
        format(amount, currency, AppLocale.DEFAULT_TAG)

    fun format(amount: BigDecimal, currency: Currency, languageTag: String): String {
        val locale = AppLocale.displayLocale(languageTag)
        val symbols = DecimalFormatSymbols(locale).apply {
            zeroDigit = '0'
            groupingSeparator = ','
            decimalSeparator = '.'
        }
        val formatter = DecimalFormat("#,##0.##", symbols)
        formatter.minimumFractionDigits = if (amount.remainder(BigDecimal.ONE).signum() == 0) 0 else Money.SCALE
        formatter.maximumFractionDigits = Money.SCALE
        val number = formatter.format(amount)
        return "$number ${currencyLabel(currency, languageTag)}"
    }

    fun currencyLabel(currency: Currency, languageTag: String): String =
        when (currency) {
            Currency.SAR -> if (AppLocale.isEnglish(languageTag)) "SAR" else "ر.س"
            Currency.USD -> "USD"
            Currency.EUR -> "EUR"
            Currency.GBP -> "GBP"
        }
}
