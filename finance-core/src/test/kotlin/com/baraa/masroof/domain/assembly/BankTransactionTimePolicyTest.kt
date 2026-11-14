package com.baraa.masroof.domain.assembly

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class BankTransactionTimePolicyTest {
    private val local = LocalDateTime.of(2026, 8, 26, 22, 30)
    private val receivedAt = Instant.parse("2026-08-26T19:30:00Z")

    @Test
    fun everyBankWallClock_isRiyadhTimeWhateverTheDeviceZone() {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val riyadhInstant = local.atZone(ZoneId.of("Asia/Riyadh")).toInstant()
            for (bank in listOf(Bank.BANK_ALJAZIRA, Bank("BANK_SNB"), Bank("BANK_STC"), Bank.UNKNOWN)) {
                assertEquals(riyadhInstant, TransactionTiming.effectiveOccurredAt(event(bank), local, receivedAt), bank.id)
            }
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun eveningWallClock_staysInThePeriodBeforeDay27() {
        val instant = TransactionTiming.effectiveOccurredAt(event(Bank("BANK_SNB")), local, receivedAt)
        val riyadhDay = instant.atZone(BankTransactionTimePolicy.RIYADH).toLocalDate()
        val period = FinancialPeriodPolicy.periodContaining(riyadhDay)
        assertEquals(LocalDate.of(2026, 7, 27), period.startDate)
        assertEquals(LocalDate.of(2026, 8, 27), period.endDateExclusive)

        val ifWallClockWereUtc = local.atZone(ZoneOffset.UTC).toInstant()
            .atZone(BankTransactionTimePolicy.RIYADH)
            .toLocalDate()
        assertEquals(LocalDate.of(2026, 8, 27), ifWallClockWereUtc)
        assertNotEquals(period, FinancialPeriodPolicy.periodContaining(ifWallClockWereUtc))
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
