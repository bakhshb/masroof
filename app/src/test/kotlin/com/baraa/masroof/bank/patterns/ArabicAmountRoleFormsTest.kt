package com.baraa.masroof.bank.patterns

import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Every bundled bank reads the common Arabic amount roles, with or without spaces and Arabic digits. */
class ArabicAmountRoleFormsTest {
    private val banks = mapOf("BANK_ALJAZIRA" to "AlJazira", "BANK_SNB" to "SNB-AlAhli", "BANK_ALINMA" to "alinma",
        "BANK_SAB" to "SAB", "BANK_BARQ" to "barq app", "BANK_EMIRATES_NBD" to "EmiratesNBD")
    private fun adapter(bank: String) = bundledPatterns(bank)
        .let { MessagePatternSmsAdapter(it.banks.single(), PatternFactInterpreter(it)) }
    private fun parse(bank: String, body: String) =
        adapter(bank).parse(SmsParseInput("amount-form", banks.getValue(bank), body, Instant.parse("2026-10-15T10:00:00Z")))

    @Test fun labelledAmountFormsAreReadByEveryBank() {
        val forms = listOf("بمبلغ: 100 SAR", "ب١٠٠ريال", "ب100SAR", "بـ100 ريال", "بقيمة100ريال", "بقيمة 100 ريال", "مبلغ100SAR", "بمبلغ١٠٠ ريال")
        banks.keys.forEach { bank -> forms.forEach { form ->
            val result = parse(bank, "شراء عبر نقاط البيع\nبطاقة مدى: 4321\n$form\nلدى: TEST SHOP\nفي: 2026-10-15 12:30")
            assertTrue("$bank $form: $result", result is ParseResult.Success)
            val amount = (result as ParseResult.Success).event.amount!!
            assertEquals("$bank $form", 0, amount.amount.compareTo("100".toBigDecimal()))
            assertEquals("$bank $form", "SAR", amount.currency.name)
        } }
    }

    @Test fun anInlineAmountRoleIsReadGluedOrSpaced() {
        banks.keys.forEach { bank -> listOf("بقيمة100ريال", "بقيمة 100 ريال", "ب100SAR", "ب١٠٠ريال", "بمبلغ100 SAR").forEach { form ->
            val result = parse(bank, "شراء عبر نقاط البيع $form\nبطاقة مدى: 4321\nلدى: TEST SHOP\nفي: 2026-10-15 12:30")
            assertTrue("$bank $form: $result", result is ParseResult.Success)
            assertEquals("$bank $form", 0, (result as ParseResult.Success).event.amount!!.amount.compareTo("100".toBigDecimal()))
        } }
    }

    @Test fun anInlineRoleWithoutCurrencyOrWithTwoAmountsIsNotGuessed() {
        banks.keys.forEach { bank ->
            assertFalse(bank, parse(bank, "شراء عبر نقاط البيع ب100\nبطاقة مدى: 4321\nلدى: TEST SHOP\nفي: 2026-10-15 12:30") is ParseResult.Success)
            assertFalse(bank, parse(bank, "شراء عبر نقاط البيع بقيمة100ريال وبقيمة200ريال\nبطاقة مدى: 4321\nلدى: TEST SHOP\nفي: 2026-10-15 12:30") is ParseResult.Success)
        }
    }
}
