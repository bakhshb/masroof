package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.testsupport.tx
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppliedExchangeRateSyncerTest {
    @Test
    fun completePair_staysWhileAnOrphanTakesTheWholeResolution() {
        val complete = tx(
            id = "complete",
            rate = BigDecimal("9.99"),
            rateSource = ExchangeRateSource.MARKET,
            currency = Currency.USD,
        )
        val orphanRate = tx(
            id = "orphan-rate",
            rate = BigDecimal("8.888"),
            rateSource = null,
            currency = Currency.USD,
        )
        val orphanSource = tx(
            id = "orphan-source",
            rate = null,
            rateSource = ExchangeRateSource.SMS,
            currency = Currency.USD,
        )
        val resolution = SarEquivalentResolution(
            sarAmount = Money.of("38.00", Currency.SAR),
            exchangeRate = BigDecimal("3.80"),
            source = ExchangeRateSource.MARKET,
        )
        val resolutions = mapOf(
            complete.id to resolution,
            orphanRate.id to resolution,
            orphanSource.id to resolution,
        )

        val shown = AppliedExchangeRateSyncer.applyInMemory(
            listOf(complete, orphanRate, orphanSource),
            resolutions,
        ).associateBy { it.id }

        assertEquals(BigDecimal("9.99"), shown.getValue("complete").appliedExchangeRate)
        assertEquals(ExchangeRateSource.MARKET, shown.getValue("complete").exchangeRateSource)
        assertEquals(BigDecimal("3.80"), shown.getValue("orphan-rate").appliedExchangeRate)
        assertEquals(ExchangeRateSource.MARKET, shown.getValue("orphan-rate").exchangeRateSource)
        assertEquals(BigDecimal("3.80"), shown.getValue("orphan-source").appliedExchangeRate)
        assertEquals(ExchangeRateSource.MARKET, shown.getValue("orphan-source").exchangeRateSource)
    }
}
