package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class HistoricalExchangeRateIndexTest {
    private val withinWindow = Instant.parse("2026-08-15T00:00:00Z")

    @Test
    fun exactNormalizedMerchant_usesItsOwnRate() {
        val index = index(
            evidence("pe-stc", "STC", "3.70"),
            evidence("pe-pay", "STC PAY", "3.80"),
        )

        assertEquals(BigDecimal("3.70"), index.rateForMerchant("stc", Currency.USD, withinWindow))
        assertEquals(BigDecimal("3.80"), index.rateForMerchant("STC  PAY", Currency.USD, withinWindow))
    }

    @Test
    fun similarMerchantName_doesNotSupplyTheRate() {
        val index = index(evidence("pe-pay", "STC PAY", "3.80"))

        assertNull(index.rateForMerchant("STC", Currency.USD, withinWindow))
        assertNull(index.rateForMerchant("PAY", Currency.USD, withinWindow))
        assertEquals(BigDecimal("3.80"), index.rateForMerchant("STC PAY", Currency.USD, withinWindow))
    }

    @Test
    fun sarCurrency_hasNoHistoricalForeignRate() {
        val index = index(evidence("pe-stc", "STC", "3.70"))

        assertNull(index.rateForMerchant("STC", Currency.SAR, withinWindow))
    }

    @Test
    fun laterRate_doesNotApplyBeforeItsEvidenceInstant() {
        val early = Instant.parse("2026-10-01T07:00:00Z")
        val late = Instant.parse("2026-10-07T07:00:00Z")
        val records = listOf(
            evidence("pe-late", "TEST_FX_SHOP", "4.00", occurredAt = late),
            evidence("pe-early", "TEST_FX_SHOP", "3.50", occurredAt = early),
        )
        val forward = HistoricalExchangeRateIndex.build(records, emptyMap())
        val reversed = HistoricalExchangeRateIndex.build(records.asReversed(), emptyMap())

        for (index in listOf(forward, reversed)) {
            assertNull(index.rateForMerchant("TEST_FX_SHOP", Currency.USD, early.minusSeconds(1)))
            assertEquals(BigDecimal("3.50"), index.rateForMerchant("TEST_FX_SHOP", Currency.USD, early))
            assertEquals(BigDecimal("3.50"), index.rateForMerchant("TEST_FX_SHOP", Currency.USD, late.minusSeconds(1)))
            assertEquals(BigDecimal("4.00"), index.rateForMerchant("TEST_FX_SHOP", Currency.USD, late))
        }
    }

    @Test
    fun sameInstant_choosesTheLexicographicallyGreatestEventId() {
        val at = Instant.parse("2026-10-07T07:00:00Z")
        val index = HistoricalExchangeRateIndex.build(
            listOf(
                evidence("evt-z", "TEST_FX_SHOP", "4.00", occurredAt = at),
                evidence("evt-a", "TEST_FX_SHOP", "3.10", occurredAt = at),
            ).asReversed(),
            emptyMap(),
        )

        assertEquals(BigDecimal("4.00"), index.rateForMerchant("TEST_FX_SHOP", Currency.USD, at))
    }

    @Test
    fun evidenceOlderThanThirtyDays_isIneligible() {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val index = index(evidence("pe-old", "STC", "3.70", occurredAt = at))
        val oldestEligible = at.plus(HistoricalExchangeRateIndex.MAX_EVIDENCE_AGE)

        assertEquals(BigDecimal("3.70"), index.rateForMerchant("STC", Currency.USD, oldestEligible))
        assertNull(index.rateForMerchant("STC", Currency.USD, oldestEligible.plusSeconds(1)))
    }

    @Test
    fun otherCurrency_doesNotSupplyTheRate() {
        val index = index(evidence("pe-eur", "STC", "4.10", currency = Currency.EUR))

        assertNull(index.rateForMerchant("STC", Currency.USD, withinWindow))
        assertEquals(BigDecimal("4.10"), index.rateForMerchant("STC", Currency.EUR, withinWindow))
    }

    @Test
    fun alJaziraWallClock_usesRiyadhNotTheHandsetOrAStoredZone() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            val record = evidence(
                id = "pe-riyadh",
                merchant = "TEST_FX_SHOP",
                rate = "4.00",
                occurredAt = null,
                occurredAtLocal = LocalDateTime.parse("2026-10-01T10:00"),
            )
            val index = HistoricalExchangeRateIndex.build(
                parsedRecords = listOf(record),
                rawSmsById = mapOf(record.event.rawSmsId to raw(record.event.rawSmsId, Instant.parse("2026-09-01T00:00:00Z"))),
                persistedZoneIdByEventId = mapOf(record.event.id to "Pacific/Honolulu"),
            )

            assertEquals(
                BigDecimal("4.00"),
                index.rateForMerchant("TEST_FX_SHOP", Currency.USD, Instant.parse("2026-10-01T07:00:00Z")),
            )
            assertNull(
                index.rateForMerchant("TEST_FX_SHOP", Currency.USD, Instant.parse("2026-10-01T06:59:59Z")),
            )
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun otherBank_usesThePersistedZoneNotTheHandsetZone() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        try {
            val record = evidence(
                id = "pe-tokyo",
                merchant = "TEST_FX_SHOP",
                rate = "4.00",
                occurredAt = null,
                occurredAtLocal = LocalDateTime.parse("2026-10-01T10:00"),
                bank = Bank("OTHER"),
            )
            val index = HistoricalExchangeRateIndex.build(
                parsedRecords = listOf(record),
                rawSmsById = emptyMap(),
                persistedZoneIdByEventId = mapOf(record.event.id to "Asia/Tokyo"),
            )
            val tokyoInstant = LocalDateTime.parse("2026-10-01T10:00")
                .atZone(ZoneId.of("Asia/Tokyo"))
                .toInstant()

            assertEquals(BigDecimal("4.00"), index.rateForMerchant("TEST_FX_SHOP", Currency.USD, tokyoInstant))
            assertNull(index.rateForMerchant("TEST_FX_SHOP", Currency.USD, tokyoInstant.minusSeconds(1)))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun index(vararg records: ParsedEventRecord) =
        HistoricalExchangeRateIndex.build(records.toList(), emptyMap())

    private fun evidence(
        id: String,
        merchant: String,
        rate: String,
        occurredAt: Instant? = Instant.parse("2026-08-01T00:00:00Z"),
        occurredAtLocal: LocalDateTime? = null,
        currency: Currency = Currency.USD,
        bank: Bank = Bank.BANK_ALJAZIRA,
    ) = ParsedEventRecord(
        event = ParsedEvent(
            id = id,
            rawSmsId = "sms-$id",
            bank = bank,
            messageFamily = MessageFamily.PURCHASE,
            direction = null,
            amount = Money.of("10.00", currency),
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = null,
            merchant = merchant,
            counterparty = null,
            occurredAt = occurredAt,
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        details = ParsedEventDetails(
            exchangeRate = BigDecimal(rate),
            occurredAtLocal = occurredAtLocal,
        ),
    )

    private fun raw(id: String, receivedAt: Instant) = RawSms(
        id = id,
        sender = "AlJazira",
        body = "rate evidence",
        receivedAt = receivedAt,
        deviceMessageId = id,
        bodyHash = "hash-$id",
    )
}
