package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyUiFormatterTest {
    @Test
    fun wholeAmountsDoNotAddFractionalZeros_andRealCentsArePreserved() {
        assertEquals("1,234 ر.س", MoneyUiFormatter.format(Money.of("1234", Currency.SAR), "ar"))
        assertEquals("1,234.50 ر.س", MoneyUiFormatter.format(Money.of("1234.50", Currency.SAR), "ar"))
        assertEquals("1,234.01 SAR", MoneyUiFormatter.format(Money.of("1234.01", Currency.SAR), "en"))
    }
    @Test
    fun format_englishUsesSarLabel() {
        val formatted = MoneyUiFormatter.format(Money.of("1234.50", Currency.SAR), AppLocale.TAG_EN)
        assertTrue(formatted.contains("SAR"))
        assertTrue(formatted.contains("1,234.50"))
    }

    @Test
    fun format_arabicUsesRiyalSymbol() {
        val formatted = MoneyUiFormatter.format(Money.of("1234.50", Currency.SAR), AppLocale.TAG_AR)
        assertTrue(formatted.contains("ر.س"))
        assertEquals("1,234.50 ر.س", formatted)
    }

    @Test
    fun format_allCurrenciesAndSignsUseAsciiDigits_inBothLanguagesAndRegionalTags() {
        listOf("ar", "ar-SA", "en", "en-US").forEach { language ->
            Currency.entries.forEach { currency ->
                listOf("0", "-1234.5", "18500").forEach { amount ->
                    val formatted = MoneyUiFormatter.format(java.math.BigDecimal(amount), currency, language)
                    assertTrue(formatted.none { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' })
                    assertTrue(formatted.any { it in '0'..'9' })
                }
            }
        }
        assertEquals("SAR", MoneyUiFormatter.currencyLabel(Currency.SAR, "en-US"))
    }

    @Test
    fun negativeAmounts_keepLocaleDirectionMark() {
        assertEquals("\u200E-1,234.50 ر.س", MoneyUiFormatter.format(java.math.BigDecimal("-1234.50"), Currency.SAR, "ar"))
        assertEquals("\u200E-18,500 USD", MoneyUiFormatter.format(java.math.BigDecimal("-18500"), Currency.USD, "ar-SA"))
        assertEquals("-1,234.50 SAR", MoneyUiFormatter.format(java.math.BigDecimal("-1234.50"), Currency.SAR, "en-US"))
    }

    @Test
    fun zeroAndOtherCurrencies_keepTheirLabels() {
        assertEquals("0 ر.س", MoneyUiFormatter.format(java.math.BigDecimal("0"), Currency.SAR, "ar"))
        assertEquals("0 SAR", MoneyUiFormatter.format(java.math.BigDecimal("0"), Currency.SAR, "en"))
        assertEquals("1,234.01 EUR", MoneyUiFormatter.format(java.math.BigDecimal("1234.01"), Currency.EUR, "ar"))
        assertEquals("1,234.01 GBP", MoneyUiFormatter.format(java.math.BigDecimal("1234.01"), Currency.GBP, "en"))
    }

    @Test
    fun currencyLabel_respectsLanguage() {
        assertEquals("SAR", MoneyUiFormatter.currencyLabel(Currency.SAR, AppLocale.TAG_EN))
        assertEquals("ر.س", MoneyUiFormatter.currencyLabel(Currency.SAR, AppLocale.TAG_AR))
    }
}
