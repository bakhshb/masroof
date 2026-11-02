package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ForeignPurchaseSarConverterTest {
    @Test
    fun usdPurchase_convertsWithFee() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("23.00", Currency.USD),
            exchangeRate = java.math.BigDecimal("3.756957"),
            internationalFee = Money.of("1.99", Currency.SAR),
        )
        assertNotNull(sar)
        assertEquals(Currency.SAR, sar!!.currency)
        // 23 * 3.756957 = 86.409011 + 1.99 fee
        assertEquals(Money.of("88.40", Currency.SAR), sar)
    }

    @Test
    fun usdRefund_withoutFeeWhenRateInSameSms() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("6.51", Currency.USD),
            exchangeRate = java.math.BigDecimal("3.756957"),
        )
        assertNotNull(sar)
        assertEquals(Money.of("24.46", Currency.SAR), sar)
    }

    @Test
    fun eurPurchase_convertsWithoutFee() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("20.00", Currency.EUR),
            exchangeRate = java.math.BigDecimal("4.3466"),
        )
        assertNotNull(sar)
        assertEquals(Money.of("86.93", Currency.SAR), sar)
    }

    @Test
    fun gbpPurchase_convertsWithoutFee() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("10.00", Currency.GBP),
            exchangeRate = java.math.BigDecimal("5.0797"),
        )
        assertNotNull(sar)
        assertEquals(Money.of("50.80", Currency.SAR), sar)
    }

    @Test
    fun feeInPurchaseCurrency_isConvertedNotAddedAsSar() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("23.00", Currency.USD),
            exchangeRate = java.math.BigDecimal("3.756957"),
            internationalFee = Money.of("2.50", Currency.USD),
        )
        // 86.41 + 2.50 * 3.756957 (9.39)
        assertEquals(Money.of("95.80", Currency.SAR), sar)
    }

    @Test
    fun feeInAnUnrelatedCurrency_isLeftOut() {
        val sar = ForeignPurchaseSarConverter.foreignToSar(
            foreignAmount = Money.of("23.00", Currency.USD),
            exchangeRate = java.math.BigDecimal("3.756957"),
            internationalFee = Money.of("2.50", Currency.EUR),
        )
        assertEquals(Money.of("86.41", Currency.SAR), sar)
    }
}
