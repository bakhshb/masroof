package com.baraa.masroof.presentation.locale

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.presentation.dashboard.MoneyUiFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun formatLocalizedMoney(money: Money): String {
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    return MoneyUiFormatter.format(money, languageTag)
}

@Composable
fun formatLocalizedMoney(signed: com.baraa.masroof.application.dashboard.SignedMoneyAmount): String {
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    return MoneyUiFormatter.format(signed, languageTag)
}

@Composable
fun formatLocalizedTransactionDate(
    date: LocalDate,
    pattern: String = "d MMM",
): String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

