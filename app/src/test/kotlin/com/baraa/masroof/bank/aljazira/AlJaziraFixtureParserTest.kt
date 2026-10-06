package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.model.TransferOwnershipType
import com.baraa.masroof.parsing.fixtures.AlJaziraFixture
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureLoader
import com.baraa.masroof.parsing.detector.BankDetector
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.SmsParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@RunWith(Parameterized::class)
class AlJaziraFixtureParserTest(private val fixture: AlJaziraFixture) {

    private val pipeline = AlJaziraParsingPipeline()

    @Test
    fun routesThroughRegistry_andAdapterParsesIdenticallyWithoutRedetecting() {
        val detectCalls = AtomicInteger(0)
        val countingDetector = object : BankDetector {
            override fun detect(sender: String, body: String): BankDetectionResult {
                detectCalls.incrementAndGet()
                return AlJaziraBankDetector().detect(sender, body)
            }
        }
        val adapter = AlJaziraSmsAdapter(detector = countingDetector)
        val route = BankSmsRegistry(listOf(adapter)).route(fixture.sender, fixture.body)
        assertTrue("${fixture.id} must route to AlJazira, got $route", route is BankRoutingResult.Matched)
        assertEquals(1, detectCalls.get())

        val input = SmsParseInput(
            rawSmsId = fixture.id,
            sender = fixture.sender,
            body = fixture.body,
            receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
        )
        val viaAdapter = (route as BankRoutingResult.Matched).adapter.parse(input)

        assertEquals(fixture.id, pipeline.parse(input), viaAdapter)
        assertEquals("parse must not re-run detection", 1, detectCalls.get())
    }

    @Test
    fun parsesFixtureExpectations() {
        val result = pipeline.parse(
            SmsParseInput(
                rawSmsId = fixture.id,
                sender = fixture.sender,
                body = fixture.body,
                receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
            ),
        )
        val expected = fixture.expected
        val (event, details) = unpack(result)

        assertNotNull("expected event for ${fixture.id}, got $result", event)
        val e = event!!

        assertEquals(fixture.id, Bank.BANK_ALJAZIRA, e.bank)
        assertEquals(fixture.id, MessageFamily.valueOf(expected.messageFamily), e.messageFamily)
        assertEquals(fixture.id, ParseStatus.valueOf(expected.parseStatus), e.parseStatus)

        assertEquals(fixture.id, expected.direction?.let { MoneyDirection.valueOf(it) }, e.direction)
        assertEquals(fixture.id, expected.purchaseChannel?.let { PurchaseChannel.valueOf(it) }, e.purchaseChannel)
        assertEquals(fixture.id, expected.bankNetworkType?.let { BankNetworkType.valueOf(it) }, e.bankNetworkType)

        if (expected.amount != null) {
            val currency = expected.currency?.let { Currency.valueOf(it) } ?: Currency.SAR
            assertEquals(fixture.id, Money.of(expected.amount, currency), e.amount)
        } else {
            assertNull(fixture.id, e.amount)
        }

        assertEquals(fixture.id, expected.sourceAccountLast4, e.sourceAccountRef?.maskedNumber)
        assertEquals(fixture.id, expected.destinationAccountLast4, e.destinationAccountRef?.maskedNumber)
        assertAccountBankScope(fixture.id, expected.bankNetworkType, expected.messageFamily, e)
        assertEquals(fixture.id, expected.cardLast4, e.cardRef?.last4)
        assertEquals(fixture.id, expected.merchant, e.merchant)
        assertEquals(fixture.id, expected.counterparty, e.counterparty)

        val d = details ?: ParsedEventDetails()
        assertEquals(fixture.id, expected.biller, d.biller)
        assertEquals(fixture.id, expected.billerCode, d.billerCode)
        assertEquals(fixture.id, expected.transactionReference, d.transactionReference)

        if (expected.availableBalance != null) {
            assertEquals(fixture.id, Money.of(expected.availableBalance, Currency.SAR), d.availableBalance)
        } else {
            assertNull(fixture.id, d.availableBalance)
        }
        if (expected.outstandingBalance != null) {
            assertEquals(fixture.id, Money.of(expected.outstandingBalance, Currency.SAR), d.outstandingBalance)
        } else {
            assertNull(fixture.id, d.outstandingBalance)
        }

        if (expected.occurredAt != null) {
            assertEquals(fixture.id, LocalDateTime.parse(expected.occurredAt), d.occurredAtLocal)
            // Must not invent Instant/UTC from offset-less local SMS time.
            assertNull(fixture.id, e.occurredAt)
        }

        expected.cardSmsChannel?.let { channel ->
            assertEquals(fixture.id, CardSmsChannel.valueOf(channel), d.cardSmsChannel)
        }
        expected.paymentDueDate?.let { due ->
            assertEquals(fixture.id, LocalDate.parse(due), d.paymentDueDate)
        }
        expected.exchangeRate?.let { rate ->
            assertEquals(fixture.id, BigDecimal(rate), d.exchangeRate)
        }
        expected.internationalFee?.let { fee ->
            assertEquals(fixture.id, Money.of(fee, Currency.SAR), d.internationalFee)
        }
        if (expected.labeledForeignAmount != null) {
            val currency = expected.labeledForeignCurrency?.let { Currency.valueOf(it) } ?: Currency.SAR
            assertEquals(fixture.id, Money.of(expected.labeledForeignAmount, currency), d.labeledForeignAmount)
        }
        expected.loanType?.let { loanType ->
            assertEquals(fixture.id, LoanType.valueOf(loanType), d.loanType)
        }
        expected.debitSourceAccountLast4?.let { last4 ->
            assertEquals(fixture.id, last4, d.debitSourceAccountLast4)
        }
        expected.salaryIncomeWording?.let { wording ->
            assertEquals(fixture.id, wording, d.salaryIncomeWording)
        }

        assertFalse(result.toString().contains("SELF_TRANSFER"))
        assertFalse(result.toString().contains(TransferOwnershipType.SELF_TRANSFER.name))
    }

    /**
     * AccountReference.bank is bank-scoped identity, not ownership.
     * INTER_BANK external side → [Bank.UNKNOWN]; local AlJazira side → BANK_ALJAZIRA.
     */
    private fun assertAccountBankScope(
        fixtureId: String,
        networkType: String?,
        messageFamily: String,
        event: ParsedEvent,
    ) {
        val source = event.sourceAccountRef
        val destination = event.destinationAccountRef
        when {
            networkType == "INTER_BANK" && messageFamily == "TRANSFER_OUT" -> {
                source?.let { assertEquals(fixtureId, Bank.BANK_ALJAZIRA, it.bank) }
                destination?.let { assertEquals(fixtureId, Bank.UNKNOWN, it.bank) }
            }
            networkType == "INTER_BANK" && messageFamily == "TRANSFER_IN" -> {
                source?.let { assertEquals(fixtureId, Bank.UNKNOWN, it.bank) }
                destination?.let { assertEquals(fixtureId, Bank.BANK_ALJAZIRA, it.bank) }
            }
            else -> {
                source?.let { assertEquals(fixtureId, Bank.BANK_ALJAZIRA, it.bank) }
                destination?.let { assertEquals(fixtureId, Bank.BANK_ALJAZIRA, it.bank) }
            }
        }
    }

    @Test
    fun typographyVariants_parseLikeCanonicalFixture() {
        val (canonical, canonicalDetails) = unpack(parseBody(fixture.body))
        val expected = requireNotNull(canonical) { "canonical ${fixture.id} produced no event" }
        val expectedDetails = canonicalDetails ?: ParsedEventDetails()
        for ((variantName, body) in typographyVariants(fixture.body)) {
            if (body == fixture.body) continue
            val label = "${fixture.id}[$variantName]"
            val (event, details) = unpack(parseBody(body))
            assertNotNull("$label produced no event", event)
            val e = event!!
            val d = details ?: ParsedEventDetails()
            assertEquals(label, expected.messageFamily, e.messageFamily)
            assertEquals(label, expected.parseStatus, e.parseStatus)
            assertEquals(label, expected.direction, e.direction)
            assertEquals(label, expected.purchaseChannel, e.purchaseChannel)
            assertEquals(label, expected.bankNetworkType, e.bankNetworkType)
            assertEquals(label, expected.amount, e.amount)
            assertEquals(label, expected.sourceAccountRef, e.sourceAccountRef)
            assertEquals(label, expected.destinationAccountRef, e.destinationAccountRef)
            assertEquals(label, expected.cardRef, e.cardRef)
            assertEquals(label, folded(expected.merchant), folded(e.merchant))
            assertEquals(label, folded(expected.counterparty), folded(e.counterparty))
            assertEquals(label, folded(expectedDetails.biller), folded(d.biller))
            assertEquals(label, folded(expectedDetails.transactionReference), folded(d.transactionReference))
            assertEquals(label, expectedDetails.availableBalance, d.availableBalance)
            assertEquals(label, expectedDetails.outstandingBalance, d.outstandingBalance)
            assertEquals(label, expectedDetails.occurredAtLocal, d.occurredAtLocal)
            assertEquals(label, expectedDetails.cardSmsChannel, d.cardSmsChannel)
            assertEquals(label, expectedDetails.paymentDueDate, d.paymentDueDate)
            assertEquals(label, expectedDetails.loanType, d.loanType)
            assertEquals(label, expectedDetails.salaryIncomeWording, d.salaryIncomeWording)
        }
    }

    private fun parseBody(body: String): ParseResult =
        pipeline.parse(
            SmsParseInput(
                rawSmsId = fixture.id,
                sender = fixture.sender,
                body = body,
                receivedAt = Instant.parse("2026-08-10T00:00:00Z"),
            ),
        )

    private fun folded(value: String?): String? =
        value?.let(com.baraa.masroof.core.text.ArabicTextFolding::foldForComparison)

    private fun typographyVariants(body: String): List<Pair<String, String>> = listOf(
        "bare_alef" to body.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا'),
        "yeh_for_alef_maqsura" to body.replace('ى', 'ي'),
        "tatweel" to ARABIC_WORD.replace(body) { m -> m.value.take(1) + "\u0640" + m.value.drop(1) },
        "diacritics" to ARABIC_WORD.replace(body) { m -> m.value.take(1) + "\u064E" + m.value.drop(1) },
        "bidi_marks" to body.lines().joinToString("\n") { "\u200F$it" },
        "colon_variant" to body.replace(':', '\uFE55'),
    )

    private fun unpack(result: ParseResult): Pair<ParsedEvent?, ParsedEventDetails?> = when (result) {
        is ParseResult.Success -> result.event to result.details
        is ParseResult.Partial -> result.event to result.details
        is ParseResult.ReviewRequired -> result.event to result.details
        is ParseResult.NonFinancial -> result.event to result.details
        is ParseResult.Invalid -> result.draft?.let {
            runCatching { it.copy(parseStatus = it.parseStatus ?: ParseStatus.INVALID).toParsedEvent("evt-${fixture.id}") }.getOrNull()
        } to result.draft?.details
        is ParseResult.Unsupported -> null to null
    }

    companion object {
        /** Arabic letter runs of 3+ (hamza..yeh), where tatweel / harakat are typographically plausible. */
        private val ARABIC_WORD = Regex("[\u0621-\u064A]{3,}")

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): Collection<Array<Any>> =
            AlJaziraFixtureLoader.loadAllFromClasspath().map { arrayOf(it) }
    }
}
