package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.ParsedEventDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class PersistenceMapperTest {

    @Test
    fun rawSms_roundTripsExactly() {
        val original = RawSms(
            id = "sms-1",
            sender = "AlJazira",
            body = "  keep\nexact  body  ",
            receivedAt = Instant.parse("2026-08-03T14:32:00Z"),
            deviceMessageId = "dev-42",
            bodyHash = "hash-abc",
        )
        val back = RawSmsMapper.toDomain(RawSmsMapper.toEntity(original))
        assertEquals(original, back)
    }

    @Test
    fun rawSms_nullDeviceMessageId_roundTrips() {
        val original = RawSms(
            id = "sms-2",
            sender = "AlJazira",
            body = "body",
            receivedAt = Instant.ofEpochMilli(1_725_000_000_000L),
            deviceMessageId = null,
            bodyHash = "h",
        )
        assertEquals(original, RawSmsMapper.toDomain(RawSmsMapper.toEntity(original)))
    }

    @Test
    fun money_roundTripsWithoutDoubleLoss() {
        listOf("51.99", "13258.00", "4445.67", "0.01", "99999999.99", "100.00").forEach { decimal ->
            val money = Money.of(decimal, Currency.SAR)
            val (d, c) = MoneyPersistence.toColumns(money)
            assertEquals(money, MoneyPersistence.fromColumns(d, c))
        }
    }

    @Test
    fun parsedEvent_andDetails_roundTripFully() {
        val event = ParsedEvent(
            id = "evt-1",
            rawSmsId = "sms-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            direction = MoneyDirection.OUTGOING,
            amount = Money.of("51.99", Currency.SAR),
            purchaseChannel = PurchaseChannel.ONLINE,
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destinationAccountRef = AccountReference(Bank.UNKNOWN, "0593"),
            cardRef = CardReference(Bank.BANK_ALJAZIRA, "7271"),
            merchant = "Keeta",
            counterparty = null,
            occurredAt = null,
            bankNetworkType = BankNetworkType.INTER_BANK,
            confidence = Confidence(0.95, listOf("labeled_amount:بمبلغ", "card_last4")),
            parseStatus = ParseStatus.SUCCESS,
        )
        val details = ParsedEventDetails(
            transactionReference = "TEST_REFERENCE_1",
            availableBalance = Money.of("17230.03", Currency.SAR),
            outstandingBalance = Money.of("802.62", Currency.SAR),
            biller = "TEST_BILLER",
            billerCode = "B1",
            occurredAtLocal = LocalDateTime.parse("2026-08-03T14:32:00"),
        )
        val entity = ParsedEventMapper.toEntity(event, details)
        val record = ParsedEventMapper.toRecord(entity)
        assertEquals(event, record.event)
        assertEquals(details, record.details)
        assertNull(record.event.occurredAt)
        assertEquals(LocalDateTime.parse("2026-08-03T14:32:00"), record.details.occurredAtLocal)
        assertEquals("Keeta", record.event.merchant)
        assertEquals("TEST_BILLER", record.details.biller)
        assertEquals(Money.of("51.99", Currency.SAR), record.event.amount)
        assertEquals(Money.of("17230.03", Currency.SAR), record.details.availableBalance)
        assertEquals(Money.of("802.62", Currency.SAR), record.details.outstandingBalance)
    }

    @Test
    fun bank_roundTrips_aljazira_unknown_andArbitrary() {
        listOf(Bank.BANK_ALJAZIRA, Bank.UNKNOWN, Bank("D360")).forEach { bank ->
            val event = minimalEvent(bank = bank)
            val back = ParsedEventMapper.toDomainEvent(ParsedEventMapper.toEntity(event, ParsedEventDetails()))
            assertEquals(bank, back.bank)
        }
    }

    @Test
    fun accountReference_preservesBankScope() {
        val event = minimalEvent().copy(
            sourceAccountRef = AccountReference(Bank.BANK_ALJAZIRA, "3002"),
            destinationAccountRef = AccountReference(Bank.UNKNOWN, "0593"),
        )
        val back = ParsedEventMapper.toDomainEvent(ParsedEventMapper.toEntity(event, ParsedEventDetails()))
        assertEquals(Bank.BANK_ALJAZIRA, back.sourceAccountRef?.bank)
        assertEquals("3002", back.sourceAccountRef?.maskedNumber)
        assertEquals(Bank.UNKNOWN, back.destinationAccountRef?.bank)
        assertEquals("0593", back.destinationAccountRef?.maskedNumber)
    }

    @Test
    fun occurredAtInstant_roundTripsIndependentlyOfLocal() {
        val instant = Instant.parse("2026-08-03T14:32:00Z")
        val event = minimalEvent().copy(occurredAt = instant)
        val details = ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-03T14:32:00"))
        val record = ParsedEventMapper.toRecord(ParsedEventMapper.toEntity(event, details))
        assertEquals(instant, record.event.occurredAt)
        assertEquals(LocalDateTime.parse("2026-08-03T14:32:00"), record.details.occurredAtLocal)
    }

    @Test
    fun localDateTime_doesNotBecomeZuluInstant() {
        val details = ParsedEventDetails(occurredAtLocal = LocalDateTime.parse("2026-08-03T14:32:00"))
        val entity = ParsedEventMapper.toEntity(minimalEvent(), details)
        assertNull(entity.occurredAtEpochMillis)
        assertNull(ParsedEventMapper.toDomainEvent(entity).occurredAt)
        assertEquals(
            LocalDateTime.parse("2026-08-03T14:32:00"),
            ParsedEventMapper.toDetails(entity).occurredAtLocal,
        )
    }

    @Test
    fun localDateTime_preservesFractionalSecondsAndShortForms() {
        listOf(
            "2026-08-03T14:32",
            "2026-08-03T14:32:00",
            "2026-08-03T14:32:00.123456789",
        ).forEach { text ->
            val original = LocalDateTime.parse(text)
            val entity = ParsedEventMapper.toEntity(
                minimalEvent(),
                ParsedEventDetails(occurredAtLocal = original),
            )
            assertEquals(original, ParsedEventMapper.toDetails(entity).occurredAtLocal)
            assertNull(entity.occurredAtEpochMillis)
        }
    }

    @Test
    fun nullOptionalFields_roundTrip() {
        val event = ParsedEvent(
            id = "evt-null",
            rawSmsId = "sms-null",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.OTP,
            direction = null,
            amount = null,
            purchaseChannel = null,
            sourceAccountRef = null,
            destinationAccountRef = null,
            cardRef = null,
            merchant = null,
            counterparty = null,
            occurredAt = null,
            bankNetworkType = null,
            confidence = Confidence(1.0, emptyList()),
            parseStatus = ParseStatus.NON_FINANCIAL,
        )
        val record = ParsedEventMapper.toRecord(ParsedEventMapper.toEntity(event, ParsedEventDetails()))
        assertEquals(event, record.event)
        assertEquals(ParsedEventDetails(), record.details)
    }

    @Test
    fun unrecognizedEnum_doesNotSilentlyMap() {
        val entity = ParsedEventMapper.toEntity(minimalEvent(), ParsedEventDetails())
            .copy(messageFamily = "NOT_A_REAL_FAMILY")
        assertThrows(IllegalArgumentException::class.java) {
            ParsedEventMapper.toDomainEvent(entity)
        }
    }

    @Test
    fun confidenceReasons_roundTrip() {
        val reasons = listOf("sender:AlJazira", "labeled_amount:of")
        assertEquals(reasons, ParsedEventMapper.decodeReasons(ParsedEventMapper.encodeReasons(reasons)))
        assertEquals(emptyList<String>(), ParsedEventMapper.decodeReasons(""))
    }

    private fun minimalEvent(bank: Bank = Bank.BANK_ALJAZIRA) = ParsedEvent(
        id = "evt-min",
        rawSmsId = "sms-min",
        bank = bank,
        messageFamily = MessageFamily.FEE,
        direction = MoneyDirection.OUTGOING,
        amount = Money.of("5.00", Currency.SAR),
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = null,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = Confidence(0.9),
        parseStatus = ParseStatus.SUCCESS,
    )
}
