package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class TransactionSarEquivalentResolverTest {
    private val noMarketRate = ForeignSarMarketRateProvider { _, _ -> null }

    @Test
    fun usdRefund_usesHistoricalRateFromPriorPurchase() = runBlocking {
        val purchaseBody = """
            شراء عبر الانترنت
            بطاقة ائتمانية: 7271
            لدى: CURSOR, AI POWERED IDE
            بمبلغ: USD 23.00
            في: 2026-08-06 20:22
            رسوم العمليات الدولية: 1.99
            سعر الصرف: 3.756957
        """.trimIndent()
        val refundBody = """
            بطاقة إئتمانية: إسترداد مبلغ
            بطاقة: Credit
            رقم: 7271
            من: CURSOR, AI POWERED IDE
            مبلغ: 6.51 USD
            في: 18:23 17-08-2026
        """.trimIndent()

        val purchaseEvent = parsedEvent(
            id = "pe-purchase",
            rawSmsId = "sms-purchase",
            family = MessageFamily.PURCHASE,
            merchant = "CURSOR, AI POWERED IDE",
            amount = Money.of("23.00", Currency.USD),
        )
        val refundEvent = parsedEvent(
            id = "pe-refund",
            rawSmsId = "sms-refund",
            family = MessageFamily.REFUND,
            merchant = "CURSOR, AI POWERED IDE",
        )
        val refundTx = FinancialTransaction(
            id = "tx-refund",
            type = FinancialTransactionType.REFUND,
            amount = Money.of("6.51", Currency.USD),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = "card:bank_aljazira:7271",
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-refund"),
        )

        val resolver = TransactionSarEquivalentResolver(noMarketRate)
        val resolution = resolver.resolve(
            transactions = listOf(refundTx),
            parsedRecords = listOf(
                ParsedEventRecord(
                    purchaseEvent,
                    com.baraa.masroof.parsing.model.ParsedEventDetails(
                        exchangeRate = BigDecimal("3.756957"),
                        internationalFee = Money.of("1.99", Currency.SAR),
                    ),
                ),
                ParsedEventRecord(refundEvent, com.baraa.masroof.parsing.model.ParsedEventDetails()),
            ),
            rawSmsById = mapOf(
                "sms-purchase" to raw("sms-purchase", purchaseBody),
                "sms-refund" to raw("sms-refund", refundBody),
            ),
        )["tx-refund"]

        assertNotNull(resolution)
        assertEquals(Money.of("24.46", Currency.SAR), resolution!!.sarAmount)
        assertEquals(BigDecimal("3.756957"), resolution.exchangeRate)
        assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, resolution.source)
    }

    @Test
    fun usdRefund_usesMarketRateWhenNoSmsOrHistoricalRate() = runBlocking {
        val refundBody = """
            بطاقة إئتمانية: إسترداد مبلغ
            من: UNKNOWN MERCHANT
            مبلغ: 10.00 USD
        """.trimIndent()
        val refundTx = FinancialTransaction(
            id = "tx-refund",
            type = FinancialTransactionType.REFUND,
            amount = Money.of("10.00", Currency.USD),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "UNKNOWN MERCHANT",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-refund"),
        )
        val refundEvent = parsedEvent(
            id = "pe-refund",
            rawSmsId = "sms-refund",
            family = MessageFamily.REFUND,
            merchant = "UNKNOWN MERCHANT",
        )
        val resolver = TransactionSarEquivalentResolver(
            marketRateProvider = ForeignSarMarketRateProvider { currency, _ ->
                if (currency == Currency.USD) BigDecimal("3.75") else null
            },
        )
        val resolution = resolver.resolve(
            transactions = listOf(refundTx),
            parsedRecords = listOf(
                ParsedEventRecord(refundEvent, com.baraa.masroof.parsing.model.ParsedEventDetails()),
            ),
            rawSmsById = mapOf("sms-refund" to raw("sms-refund", refundBody)),
        )["tx-refund"]

        assertNotNull(resolution)
        assertEquals(Money.of("37.50", Currency.SAR), resolution!!.sarAmount)
        assertEquals(BigDecimal("3.75"), resolution.exchangeRate)
        assertEquals(ExchangeRateSource.MARKET, resolution.source)
    }

    @Test
    fun eurRefund_usesMarketRateWhenNoSmsOrHistoricalRate() = runBlocking {
        val refundBody = """
            بطاقة إئتمانية: إسترداد مبلغ
            من: AMAZON EU
            مبلغ: 20.00 EUR
        """.trimIndent()
        val refundTx = FinancialTransaction(
            id = "tx-eur-refund",
            type = FinancialTransactionType.REFUND,
            amount = Money.of("20.00", Currency.EUR),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "AMAZON EU",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-refund"),
        )
        val refundEvent = parsedEvent(
            id = "pe-refund",
            rawSmsId = "sms-refund",
            family = MessageFamily.REFUND,
            merchant = "AMAZON EU",
        )
        val resolver = TransactionSarEquivalentResolver(
            marketRateProvider = ForeignSarMarketRateProvider { currency, _ ->
                if (currency == Currency.EUR) BigDecimal("4.3466") else null
            },
        )
        val resolution = resolver.resolve(
            transactions = listOf(refundTx),
            parsedRecords = listOf(
                ParsedEventRecord(refundEvent, com.baraa.masroof.parsing.model.ParsedEventDetails()),
            ),
            rawSmsById = mapOf("sms-refund" to raw("sms-refund", refundBody)),
        )["tx-eur-refund"]

        assertNotNull(resolution)
        assertEquals(Money.of("86.93", Currency.SAR), resolution!!.sarAmount)
        assertEquals(BigDecimal("4.3466"), resolution.exchangeRate)
        assertEquals(ExchangeRateSource.MARKET, resolution.source)
    }

    @Test
    fun similarMerchant_doesNotHideTheMarketRate() = runBlocking {
        val stcPay = parsedEvent(
            id = "pe-pay",
            rawSmsId = "sms-pay",
            family = MessageFamily.PURCHASE,
            merchant = "STC PAY",
            amount = Money.of("10.00", Currency.USD),
        )
        val stc = FinancialTransaction(
            id = "tx-stc",
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("8.00", Currency.USD),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "STC",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-stc"),
        )
        val stcEvent = parsedEvent(
            id = "pe-stc",
            rawSmsId = "sms-stc",
            family = MessageFamily.PURCHASE,
            merchant = "STC",
            amount = Money.of("8.00", Currency.USD),
        )
        val resolver = TransactionSarEquivalentResolver(
            marketRateProvider = ForeignSarMarketRateProvider { currency, _ ->
                if (currency == Currency.USD) BigDecimal("3.75") else null
            },
        )
        val resolution = resolver.resolve(
            transactions = listOf(stc),
            parsedRecords = listOf(
                ParsedEventRecord(
                    stcPay,
                    com.baraa.masroof.parsing.model.ParsedEventDetails(
                        exchangeRate = BigDecimal("3.80"),
                    ),
                ),
                ParsedEventRecord(stcEvent, com.baraa.masroof.parsing.model.ParsedEventDetails()),
            ),
            rawSmsById = mapOf(
                "sms-pay" to raw("sms-pay", "body"),
                "sms-stc" to raw("sms-stc", "body"),
            ),
        )["tx-stc"]

        assertNotNull(resolution)
        assertEquals(BigDecimal("3.75"), resolution!!.exchangeRate)
        assertEquals(ExchangeRateSource.MARKET, resolution.source)
    }

    @Test
    fun linkedSmsRate_winsBeforeHistoricalMerchantEvidence() = runBlocking {
        val linked = parsedEvent(
            id = "pe-stc",
            rawSmsId = "sms-stc",
            family = MessageFamily.PURCHASE,
            merchant = "STC",
            amount = Money.of("8.00", Currency.USD),
        )
        val historical = parsedEvent(
            id = "pe-stc-old",
            rawSmsId = "sms-old",
            family = MessageFamily.PURCHASE,
            merchant = "STC",
            amount = Money.of("10.00", Currency.USD),
        )
        val transaction = FinancialTransaction(
            id = "tx-stc",
            type = FinancialTransactionType.EXPENSE,
            amount = Money.of("8.00", Currency.USD),
            occurredAt = Instant.parse("2026-08-17T15:23:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "STC",
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf("pe-stc"),
        )
        val resolver = TransactionSarEquivalentResolver(
            marketRateProvider = ForeignSarMarketRateProvider { _, _ -> BigDecimal("9.99") },
        )
        val resolution = resolver.resolve(
            transactions = listOf(transaction),
            parsedRecords = listOf(
                ParsedEventRecord(
                    linked,
                    com.baraa.masroof.parsing.model.ParsedEventDetails(
                        exchangeRate = BigDecimal("3.70"),
                    ),
                ),
                ParsedEventRecord(
                    historical,
                    com.baraa.masroof.parsing.model.ParsedEventDetails(
                        exchangeRate = BigDecimal("3.10"),
                    ),
                ),
            ),
            rawSmsById = mapOf(
                "sms-stc" to raw("sms-stc", "body"),
                "sms-old" to raw("sms-old", "body"),
            ),
        )["tx-stc"]

        assertNotNull(resolution)
        assertEquals(BigDecimal("3.70"), resolution!!.exchangeRate)
        assertEquals(ExchangeRateSource.SMS, resolution.source)
    }

    @Test
    fun octoberPurchase_doesNotInheritALaterMerchantRate_inEitherEvidenceOrder() = runBlocking {
        val october1 = purchase(
            id = "tx-oct1",
            eventId = "pe-oct1",
            at = riyadh("2026-10-01T10:00"),
        )
        val october7 = purchase(
            id = "tx-oct7",
            eventId = "pe-oct7",
            at = riyadh("2026-10-07T10:00"),
        )
        val october8 = purchase(
            id = "tx-oct8",
            eventId = "pe-oct8",
            at = riyadh("2026-10-08T10:00"),
        )
        val records = listOf(
            record(october1, rate = null),
            record(october7, rate = BigDecimal("4.00")),
            record(october8, rate = null),
        )
        val rawSms = records.associate { record ->
            record.event.rawSmsId to raw(
                record.event.rawSmsId,
                "body",
                Instant.parse("2026-10-01T07:00:00Z"),
            )
        }
        val resolver = TransactionSarEquivalentResolver(noMarketRate)

        val forward = resolver.resolve(listOf(october1, october7, october8), records, rawSms)
        val reversed = resolver.resolve(listOf(october8, october1, october7), records.asReversed(), rawSms)

        assertNull(forward["tx-oct1"])
        assertNull(reversed["tx-oct1"])
        assertEquals(ExchangeRateSource.SMS, forward["tx-oct7"]!!.source)
        assertEquals(BigDecimal("4.00"), forward["tx-oct7"]!!.exchangeRate)
        assertEquals(Money.of("40.00", Currency.SAR), forward["tx-oct7"]!!.sarAmount)
        assertEquals(forward["tx-oct7"], reversed["tx-oct7"])
        assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, forward["tx-oct8"]!!.source)
        assertEquals(BigDecimal("4.00"), forward["tx-oct8"]!!.exchangeRate)
        assertEquals(Money.of("40.00", Currency.SAR), forward["tx-oct8"]!!.sarAmount)
        assertEquals(forward["tx-oct8"], reversed["tx-oct8"])
    }

    @Test
    fun completePair_isUsedWhenTheDatedRateWouldDiffer() = runBlocking {
        val transaction = purchase(
            id = "tx-frozen",
            eventId = "pe-frozen",
            at = riyadh("2026-10-08T10:00"),
        ).copy(
            appliedExchangeRate = BigDecimal("9.99"),
            exchangeRateSource = ExchangeRateSource.HISTORICAL_MERCHANT,
        )
        val evidence = record(
            purchase(id = "tx-rate", eventId = "pe-rate", at = riyadh("2026-10-07T10:00")),
            rate = BigDecimal("4.00"),
        )
        val resolver = TransactionSarEquivalentResolver(noMarketRate)
        val resolution = resolver.resolve(
            transactions = listOf(transaction),
            parsedRecords = listOf(record(transaction, rate = null), evidence),
            rawSmsById = emptyMap(),
        )["tx-frozen"]

        assertNotNull(resolution)
        assertEquals(BigDecimal("9.99"), resolution!!.exchangeRate)
        assertEquals(ExchangeRateSource.HISTORICAL_MERCHANT, resolution.source)
    }

    @Test
    fun marketRate_usesTheBankZoneNotTheHandsetZone() = runBlocking {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            var seen: LocalDate? = null
            val transaction = purchase(
                id = "tx-late",
                eventId = "pe-late",
                at = Instant.parse("2026-10-01T22:30:00Z"),
            ).copy(occurredAtZone = "Asia/Riyadh")
            val resolver = TransactionSarEquivalentResolver { _, date ->
                seen = date
                BigDecimal("3.75")
            }
            val resolution = resolver.resolve(
                transactions = listOf(transaction),
                parsedRecords = listOf(record(transaction, rate = null)),
                rawSmsById = emptyMap(),
            )["tx-late"]

            assertEquals(LocalDate.parse("2026-10-02"), seen)
            assertEquals(ExchangeRateSource.MARKET, resolution!!.source)
            assertEquals(BigDecimal("3.75"), resolution.exchangeRate)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun marketRate_usesAlJaziraPolicyWhenTheTransactionHasNoZone() = runBlocking {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            var seen: LocalDate? = null
            val transaction = purchase(
                id = "tx-late",
                eventId = "pe-late",
                at = Instant.parse("2026-10-01T22:30:00Z"),
            ).copy(occurredAtZone = null)
            val resolver = TransactionSarEquivalentResolver { _, date ->
                seen = date
                BigDecimal("3.75")
            }
            resolver.resolve(
                transactions = listOf(transaction),
                parsedRecords = listOf(record(transaction, rate = null)),
                rawSmsById = emptyMap(),
            )

            assertEquals(LocalDate.parse("2026-10-02"), seen)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun riyadh(local: String): Instant =
        LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Riyadh")).toInstant()

    private fun purchase(id: String, eventId: String, at: Instant) = FinancialTransaction(
        id = id,
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of("10.00", Currency.USD),
        occurredAt = at,
        sourceContainerId = "card:bank_aljazira:7271",
        destinationContainerId = null,
        merchant = "TEST_FX_SHOP",
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = listOf(eventId),
        occurredAtZone = "Asia/Riyadh",
    )

    private fun record(transaction: FinancialTransaction, rate: BigDecimal?) = ParsedEventRecord(
        event = parsedEvent(
            id = transaction.linkedParsedEventIds.single(),
            rawSmsId = "sms-${transaction.id}",
            family = MessageFamily.PURCHASE,
            merchant = transaction.merchant ?: "TEST_FX_SHOP",
            amount = transaction.amount,
        ).copy(occurredAt = null),
        details = ParsedEventDetails(
            exchangeRate = rate,
            occurredAtLocal = LocalDateTime.ofInstant(transaction.occurredAt, ZoneId.of("Asia/Riyadh")),
        ),
    )

    private fun parsedEvent(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        merchant: String,
        amount: Money? = null,
    ) = ParsedEvent(
        id = id,
        rawSmsId = rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = family,
        direction = null,
        amount = amount,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = merchant,
        counterparty = null,
        occurredAt = null,
        bankNetworkType = null,
        confidence = com.baraa.masroof.domain.model.Confidence(1.0, emptyList()),
        parseStatus = ParseStatus.SUCCESS,
    )

    private fun raw(
        id: String,
        body: String,
        receivedAt: Instant = Instant.parse("2026-08-17T15:23:00Z"),
    ) = RawSms(
        id = id,
        sender = "AlJazira",
        body = body,
        receivedAt = receivedAt,
        deviceMessageId = id,
        bodyHash = "hash-$id",
    )
}
