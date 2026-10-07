package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class HistoricalExchangeRateIndexTest {
    @Test
    fun exactNormalizedMerchant_usesItsOwnRate() {
        val index = index(
            evidence("pe-stc", "STC", "3.70"),
            evidence("pe-pay", "STC PAY", "3.80"),
        )

        assertEquals(BigDecimal("3.70"), index.rateForMerchant("stc", Currency.USD))
        assertEquals(BigDecimal("3.80"), index.rateForMerchant("STC  PAY", Currency.USD))
    }

    @Test
    fun similarMerchantName_doesNotSupplyTheRate() {
        val index = index(evidence("pe-pay", "STC PAY", "3.80"))

        assertNull(index.rateForMerchant("STC", Currency.USD))
        assertNull(index.rateForMerchant("PAY", Currency.USD))
        assertEquals(BigDecimal("3.80"), index.rateForMerchant("STC PAY", Currency.USD))
    }

    @Test
    fun sarCurrency_hasNoHistoricalForeignRate() {
        val index = index(evidence("pe-stc", "STC", "3.70"))

        assertNull(index.rateForMerchant("STC", Currency.SAR))
    }

    private fun index(vararg records: ParsedEventRecord) =
        HistoricalExchangeRateIndex.build(records.toList(), emptyMap())

    private fun evidence(id: String, merchant: String, rate: String) = ParsedEventRecord(
        event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = null,
            amount = Money.of("10.00", Currency.USD),
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = null,
            merchant = merchant,
            counterparty = null,
            occurredAt = Instant.parse("2026-08-01T00:00:00Z"),
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        details = ParsedEventDetails(exchangeRate = BigDecimal(rate)),
    )
}
