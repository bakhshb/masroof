package com.baraa.masroof.ui.components

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Existing production display formatting only; never converts currencies or computes totals. */
object MasroofMoneyDisplay {
    fun format(amount: BigDecimal, currencyCode: String, languageTag: String, scale: Int = 2): String {
        val locale = if (isEnglish(languageTag)) Locale.ENGLISH else Locale.Builder().setLanguage("ar")
            .setUnicodeLocaleKeyword("nu", "latn").build()
        val symbols = DecimalFormatSymbols(locale).apply {
            zeroDigit = '0'; groupingSeparator = ','; decimalSeparator = '.'
        }
        val formatter = DecimalFormat("#,##0.##", symbols)
        formatter.minimumFractionDigits = if (amount.remainder(BigDecimal.ONE).signum() == 0) 0 else scale
        formatter.maximumFractionDigits = scale
        return "${formatter.format(amount)} ${currencyLabel(currencyCode, languageTag)}"
    }

    fun currencyLabel(code: String, languageTag: String): String =
        if (code == "SAR" && !isEnglish(languageTag)) "ر.س" else code

    private fun isEnglish(tag: String) = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT) == "en"
}
