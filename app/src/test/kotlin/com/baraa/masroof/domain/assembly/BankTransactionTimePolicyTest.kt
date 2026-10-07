package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BankTransactionTimePolicyTest {
    private val local = LocalDateTime.of(2026, 8, 26, 22, 30)
    private val receivedAt = Instant.parse("2026-08-26T19:30:00Z")

    @Test
    fun alJaziraWallClock_ignoresTheDeviceZone() {
        val tokyo = TransactionTiming.effectiveOccurredAt(
            event = event(Bank.BANK_ALJAZIRA),
            occurredAtLocal = local,
            receivedAt = receivedAt,
            zoneId = ZoneId.of("Asia/Tokyo"),
        )
        val newYork = TransactionTiming.effectiveOccurredAt(
            event = event(Bank.BANK_ALJAZIRA),
            occurredAtLocal = local,
            receivedAt = receivedAt,
            zoneId = ZoneId.of("America/New_York"),
        )
        val riyadhInstant = local.atZone(BankTransactionTimePolicy.ALJAZIRA).toInstant()
        assertEquals(riyadhInstant, tokyo)
        assertEquals(tokyo, newYork)
        assertEquals(LocalDate.of(2026, 8, 26), tokyo.atZone(BankTransactionTimePolicy.ALJAZIRA).toLocalDate())
    }

    @Test
    fun alJaziraEvening_staysInThePeriodBeforeDay27() {
        val instant = TransactionTiming.effectiveOccurredAt(
            event = event(Bank.BANK_ALJAZIRA),
            occurredAtLocal = local,
            receivedAt = receivedAt,
            zoneId = ZoneOffset.UTC,
        )
        val riyadhDay = instant.atZone(BankTransactionTimePolicy.ALJAZIRA).toLocalDate()
        val period = FinancialPeriodPolicy.periodContaining(riyadhDay)
        assertEquals(LocalDate.of(2026, 7, 27), period.startDate)
        assertEquals(LocalDate.of(2026, 8, 27), period.endDateExclusive)

        val ifWallClockWereUtc = local.atZone(ZoneOffset.UTC).toInstant()
            .atZone(BankTransactionTimePolicy.ALJAZIRA)
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 8, 27), ifWallClockWereUtc)
        assertNotEquals(period, FinancialPeriodPolicy.periodContaining(ifWallClockWereUtc))
    }

    @Test
    fun unknownBank_reusesThePersistedZoneInsteadOfTheCurrentDeviceZone() {
        val first = TransactionTiming.effectiveOccurredAt(
            event = event(Bank("OTHER")),
            occurredAtLocal = local,
            receivedAt = receivedAt,
            zoneId = ZoneId.of("Asia/Tokyo"),
        )
        val again = TransactionTiming.effectiveOccurredAt(
            event = event(Bank("OTHER")),
            occurredAtLocal = local,
            receivedAt = receivedAt,
            zoneId = ZoneId.of("America/New_York"),
            persistedZoneId = "Asia/Tokyo",
        )
        assertEquals(first, again)
        assertEquals("Asia/Tokyo", TransactionTiming.zoneFor(Bank("OTHER"), "Asia/Tokyo", ZoneId.of("UTC")).id)
    }

    private fun event(bank: Bank) = ParsedEvent(
        id = "pe",
        rawSmsId = "sms",
        bank = bank,
        messageFamily = MessageFamily.PURCHASE,
        direction = MoneyDirection.OUTGOING,
        amount = null,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = Confidence(1.0),
        parseStatus = ParseStatus.SUCCESS,
    )
}
