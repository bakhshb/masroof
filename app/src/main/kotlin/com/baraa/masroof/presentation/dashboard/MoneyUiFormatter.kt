package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal

object MoneyUiFormatter {
    fun format(money: Money): String = format(money, AppLocale.DEFAULT_TAG)

    fun format(money: Money, languageTag: String): String =
        format(money.amount, money.currency, languageTag)

    fun format(signed: SignedMoneyAmount): String = format(signed, AppLocale.DEFAULT_TAG)

    fun format(signed: SignedMoneyAmount, languageTag: String): String =
        format(signed.amount, signed.currency, languageTag)

    fun format(amount: BigDecimal, currency: Currency): String =
        format(amount, currency, AppLocale.DEFAULT_TAG)

    fun format(amount: BigDecimal, currency: Currency, languageTag: String): String =
        com.baraa.masroof.ui.components.MasroofMoneyDisplay.format(amount, currency.name, languageTag, Money.SCALE)

    fun currencyLabel(currency: Currency, languageTag: String): String =
        com.baraa.masroof.ui.components.MasroofMoneyDisplay.currencyLabel(currency.name, languageTag)
}
