package com.baraa.masroof.bank.patterns

import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.engine.bank.CardChannel
import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.ParseStatus
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.isStatementSms
import com.baraa.masroof.parsing.model.SmsParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Fictional credit-card statements: a statement records the due facts, never spending. */
class CardStatementReadingTest {
    private val snb = "تم اصدار الكشف الإلكتروني لبطاقتك الائتمانية **8765\nمبلغ كشف الحساب 123.45 ريال\nالمبلغ المستحق 12.34 ريال\nتاريخ الاستحقاق 20/11/2026"
    private val sab = "عزيزي العميل،\nتم إصدار كشف حساب بطاقة Visa Noon الائتمانية (8765)\nرقم الحساب: 1234567890\nالمبلغ المستحق: SAR 12.34\nتاريخ الاستحقاق: 2026-11-20 00:00:00"

    @Test fun snbStatementRecordsTheDueWithoutTurningTheStatementTotalIntoSpending() {
        verify(parse("BANK_SNB", "SNB-AlAhli", snb))
        verify(parse("BANK_SNB", "SNB-AlAhli", snb.replace("123.45", "-123.45")))
    }

    @Test fun sabStatementDueTimestampIsNotATransactionTimeOrAnAccount() {
        for (body in listOf(sab, sab.removePrefix("عزيزي العميل،\n"))) {
            val result = parse("BANK_SAB", "SAB", body)
            verify(result)
            assertNull(result.facts.sourceAccount)
            assertNull(result.facts.destinationAccount)
            assertNull(result.facts.occurredAtLocal)
        }
    }

    @Test fun courtesyHeaderCannotHideAMovementOrTurnOtherTextIntoAStatement() {
        for (body in listOf("عزيزي العميل،\nشراء\nمبلغ: 43.25 SAR\nبطاقة:8765",
            "عزيزي العميل،\nتم إصدار كشف حساب بطاقة Visa Noon الائتمانية (8765)\nالمبلغ المستحق: SAR 12.34\nشراء\nمبلغ: 43.25 SAR")) {
            assertEquals(body, ParseStatus.REVIEW_REQUIRED, parse("BANK_SAB", "SAB", body).status)
        }
        val invalid = parse("BANK_SAB", "SAB", sab.replace("2026-11-20", "2026-02-30"))
        assertEquals(ParseStatus.REVIEW_REQUIRED, invalid.status)
        assertNull(invalid.facts.paymentDueDate)
    }

    @Test fun statementReachesTheCardDashboardAsAStatementWithItsDue() {
        for ((bank, sender, body) in listOf(Triple("BANK_SNB", "SNB-AlAhli", snb), Triple("BANK_SAB", "SAB", sab))) {
            val bundled = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/$bank.patterns.json").readText())
            val result = MessagePatternSmsAdapter(bundled.banks.single(), PatternFactInterpreter(bundled))
                .parse(SmsParseInput("statement", sender, body, Instant.parse("2026-10-20T12:00:00Z")))
            assertTrue(result.toString(), result is ParseResult.NonFinancial)
            result as ParseResult.NonFinancial
            assertEquals("8765", result.event?.cardRef?.last4)
            assertEquals(CardSmsChannel.STATEMENT, result.details.cardSmsChannel)
            assertTrue(result.details.isStatementSms())
            assertEquals("12.34", result.details.outstandingBalance?.amount?.toPlainString())
            assertEquals(LocalDate.of(2026, 11, 20), result.details.paymentDueDate)
        }
    }

    private fun verify(result: Interpretation) {
        assertEquals(result.diagnostics.toString(), ParseStatus.NON_FINANCIAL, result.status)
        assertEquals(MessageFamily.BALANCE_NOTICE, result.facts.family)
        assertEquals(CardChannel.STATEMENT, result.facts.cardChannel)
        assertEquals("8765", result.facts.card?.last4)
        assertEquals("12.34", result.facts.outstandingBalance?.amount?.toPlainString())
        assertEquals(LocalDate.of(2026, 11, 20), result.facts.paymentDueDate)
        assertNull(result.facts.amount)
    }

    private fun parse(bank: String, sender: String, body: String): Interpretation {
        val definitions = MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/$bank.patterns.json").readText())
        return PatternFactInterpreter(definitions).interpretRouted(MessageInput("fictional-statement", sender, body,
            Instant.parse("2026-10-20T12:00:00Z"), true), BankId(bank))
    }
}
