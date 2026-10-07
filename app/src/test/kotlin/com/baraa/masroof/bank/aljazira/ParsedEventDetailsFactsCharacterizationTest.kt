package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.normalizer.MessageNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Locks parse-time dashboard facts on [com.baraa.masroof.parsing.model.ParsedEventDetails].
 */
class ParsedEventDetailsFactsCharacterizationTest {
    private val parser = AlJaziraMessageParser()
    private val normalizer = MessageNormalizer()

    @Test
    fun creditPurchase_populatesCreditChannelAndBalances() {
        val body = """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة ائتمانية: 7271
            لدى: Ramadan Gifts
            بمبلغ: 75.00 SAR
            الرصيد المتاح: 14569.09 SAR
            إجمالي المبلغ المستحق:3921.11 SAR
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals(CardSmsChannel.CREDIT, details.cardSmsChannel)
        assertNotNull(details.availableBalance)
        assertNotNull(details.outstandingBalance)
    }

    @Test
    fun unlabeledCardPurchaseWithAvailableBalance_isCredit() {
        val body = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            الرصيد المتاح: SAR 17230.03
        """.trimIndent()
        assertEquals(CardSmsChannel.CREDIT, parseDetails(body).cardSmsChannel)
    }

    @Test
    fun cardLineWithoutCreditOrDebitEvidence_staysUnclassified() {
        val body = """
            POS Purchase
            Card: 4521
            At: TEST_MERCHANT_POS
            Amount: 89.50 SAR
        """.trimIndent()
        assertEquals(null, parseAny(body).cardSmsChannel)
    }

    @Test
    fun accountBalanceNotice_isNotACreditCardChannel() {
        val body = """
            إشعار رصيد
            حساب: 3001
            الرصيد المتاح: SAR 17230.03
        """.trimIndent()
        assertEquals(null, parseAny(body).cardSmsChannel)
    }

    @Test
    fun debitMarkerWinsOverAvailableBalance() {
        val body = """
            شراء من نقاط البيع
            بطاقة مدى: 2210
            خصمت من حساب: 3001
            بمبلغ: 51.99 SAR
            الرصيد المتاح: SAR 100.00
        """.trimIndent()
        assertEquals(CardSmsChannel.DEBIT, parseDetails(body).cardSmsChannel)
    }

    @Test
    fun madaPurchase_populatesDebitChannel() {
        val body = """
            شراء عبر نقاط البيع (Google Pay)
            بطاقة مدى: 8219
            بمبلغ: 127.00 SAR
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals(CardSmsChannel.DEBIT, details.cardSmsChannel)
    }

    @Test
    fun internationalPurchase_populatesExchangeRateAndFee() {
        val body = """
            بمبلغ: USD 23.00
            رسوم العمليات الدولية: 1.99
            سعر الصرف: 3.756957
        """.trimIndent()
        val details = parseDetails(body)
        assertNotNull(details.exchangeRate)
        assertNotNull(details.internationalFee)
        assertEquals(Currency.USD, details.labeledForeignAmount?.currency)
    }

    @Test
    fun financingInstallment_populatesLoanType() {
        val body = """
            خصم: قسط تمويل
            من: 3001
            القسط: SAR 3,036.11
            لـ: تمويل شخصي
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals(LoanType.PERSONAL, details.loanType)
    }

    @Test
    fun madaPurchaseWithSourceAccount_populatesDebitSourceAccountLast4() {
        val body = """
            شراء من نقاط البيع
            بطاقة مدى: 2210
            خصمت من حساب: 3001
            بمبلغ: 51.99 SAR
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals("3001", details.debitSourceAccountLast4)
    }

    @Test
    fun salaryTransferIn_populatesSalaryIncomeWording() {
        val body = """
            حوالة واردة راتب
            مبلغ: SAR 3,191.68
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals(true, details.salaryIncomeWording)
    }

    @Test
    fun statementSms_populatesStatementChannelAndDueDate() {
        val body = """
            إصدار كشف حساب
            المبلغ المستحق: 3921.11 SAR
            تاريخ الاستحقاق: 15/08/2026
        """.trimIndent()
        val details = parseDetails(body)
        assertEquals(CardSmsChannel.STATEMENT, details.cardSmsChannel)
        assertEquals(java.time.LocalDate.of(2026, 8, 15), details.paymentDueDate)
    }

    private fun parseAny(body: String) =
        when (
            val result = parser.parse(
                SmsParseInput(
                    rawSmsId = "sms-1",
                    sender = "AlJazira",
                    body = body,
                    receivedAt = Instant.parse("2026-08-03T10:24:00Z"),
                ),
                normalizer.normalize(body),
            )
        ) {
            is ParseResult.Success -> result.details
            is ParseResult.Partial -> result.details
            is ParseResult.ReviewRequired -> result.details
            is ParseResult.NonFinancial -> result.details
            else -> error("Unexpected parse result: $result")
        }

    private fun parseDetails(body: String) =
        parseAny(body).also { details ->
            assertTrue(
                details.cardSmsChannel != null ||
                    details.exchangeRate != null ||
                    details.paymentDueDate != null ||
                    details.loanType != null ||
                    details.debitSourceAccountLast4 != null ||
                    details.salaryIncomeWording != null,
            )
        }
}
