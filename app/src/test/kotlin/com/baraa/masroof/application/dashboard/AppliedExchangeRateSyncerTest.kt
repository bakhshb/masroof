package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AppliedExchangeRateSyncerTest {
    @Test
    fun completePair_staysWhileAnOrphanTakesTheWholeResolution() {
        val complete = transaction(
            id = "complete",
            rate = BigDecimal("9.99"),
            source = ExchangeRateSource.MARKET,
        )
        val orphanRate = transaction(
            id = "orphan-rate",
            rate = BigDecimal("8.888"),
            source = null,
        )
        val orphanSource = transaction(
            id = "orphan-source",
            rate = null,
            source = ExchangeRateSource.SMS,
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

    private fun transaction(
        id: String,
        rate: BigDecimal?,
        source: ExchangeRateSource?,
    ) = FinancialTransaction(
        id = id,
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of("10.00", Currency.USD),
        occurredAt = Instant.parse("2026-08-05T11:05:00Z"),
        sourceContainerId = null,
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = emptyList(),
        appliedExchangeRate = rate,
        exchangeRateSource = source,
    )
}
