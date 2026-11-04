package com.baraa.masroof.parsing.model

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class ParseResultSemanticsTest {

    private fun event(status: ParseStatus, amount: Money? = Money.of("10.00", Currency.SAR)) =
        ParsedEventDraft(
            rawSmsId = "sms-1",
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = MessageFamily.PURCHASE,
            amount = amount,
            merchant = "Shop",
            confidence = Confidence(0.9),
            parseStatus = status,
        ).toParsedEvent("evt-1")

    @Test
    fun success_acceptsOnlySuccessParseStatus() {
        assertEquals(ParseStatus.SUCCESS, ParseResult.Success(event(ParseStatus.SUCCESS)).event.parseStatus)
        assertThrows(IllegalArgumentException::class.java) {
            ParseResult.Success(event(ParseStatus.REVIEW_REQUIRED))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParseResult.Success(event(ParseStatus.PARTIAL))
        }
    }

    @Test
    fun partial_rejectsNonPartialEventStatus() {
        assertThrows(IllegalArgumentException::class.java) {
            ParseResult.Partial(
                draft = ParsedEventDraft(rawSmsId = "sms-x"),
                event = event(ParseStatus.SUCCESS),
                findings = emptyList(),
            )
        }
    }

    @Test
    fun reviewRequired_rejectsNonReviewEventStatus() {
        assertNull(ParseResult.ReviewRequired(draft = null, event = null, findings = emptyList(), reasons = listOf("x")).event)
        assertThrows(IllegalArgumentException::class.java) {
            ParseResult.ReviewRequired(
                draft = null,
                event = event(ParseStatus.SUCCESS),
                findings = emptyList(),
                reasons = listOf("x"),
            )
        }
    }

    @Test
    fun toParsedEvent_rejectsSuccessWithoutAmountForFinancialFamily() {
        assertThrows(IllegalArgumentException::class.java) {
            ParsedEventDraft(
                rawSmsId = "sms-2",
                bank = Bank.BANK_ALJAZIRA,
                messageFamily = MessageFamily.PURCHASE,
                confidence = Confidence(0.5),
                parseStatus = ParseStatus.SUCCESS,
            ).toParsedEvent("evt-bad")
        }
    }

    @Test
    fun draft_carriesOccurredAtOnlyWhenGivenExplicitly() {
        val at = Instant.parse("2026-08-01T00:00:00Z")
        assertNull(ParsedEventDraft(rawSmsId = "sms-2").occurredAt)
        assertEquals(at, ParsedEventDraft(rawSmsId = "sms-2", occurredAt = at).occurredAt)
    }
}
