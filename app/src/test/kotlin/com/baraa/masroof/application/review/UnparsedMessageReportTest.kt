package com.baraa.masroof.application.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnparsedMessageReportTest {
    @Test
    fun everyDigitOfEveryScriptIsMasked_andTheWordingStays() {
        val body = "شراء بطاقة ٧٢٧١ بمبلغ 51.99 SAR\nفي 13-11-2026 ۱۲:۰۵ حساب **3001"
        val masked = UnparsedMessageReport.mask(body)
        assertEquals("شراء بطاقة #### بمبلغ ##.## SAR\nفي ##-##-#### ##:## حساب **####", masked)
        assertFalse(masked.any(Char::isDigit))
        assertEquals("", UnparsedMessageReport.mask(""))
    }
}
