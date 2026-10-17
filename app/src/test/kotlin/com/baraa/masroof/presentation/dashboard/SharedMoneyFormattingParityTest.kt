package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.core.money.Currency
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedMoneyFormattingParityTest {
    @Test fun extractedFormattingMatchesOriginalIncludingLocaleDirectionMarks() {
        listOf("ar", "ar-SA", "en", "en-US").forEach { language ->
            Currency.entries.forEach { currency ->
                listOf("0", "-1234.50", "1234.01", "18500").forEach { amount ->
                    assertEquals("$language $currency $amount",
                        FrozenMoneyUiFormatter.format(BigDecimal(amount), currency, language),
                        MoneyUiFormatter.format(BigDecimal(amount), currency, language))
                }
            }
        }
    }
}
