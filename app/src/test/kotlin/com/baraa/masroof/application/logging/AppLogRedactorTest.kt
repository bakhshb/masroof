package com.baraa.masroof.application.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLogRedactorTest {
    @Test
    fun redact_masksClassicPat() {
        val sanitized = AppLogRedactor.redact("token ghp_1234567890123456789012345678901234")
        assertFalse(sanitized.contains("ghp_1234567890123456789012345678901234"))
    }

    @Test
    fun redact_masksFineGrainedPat() {
        val sanitized = AppLogRedactor.redact("Bearer github_pat_abcdefghijklmnopqrstuvwxyz")
        assertFalse(sanitized.contains("github_pat_abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun redact_masksPanLikeSequences() {
        val sanitized = AppLogRedactor.redact("card 4111 1111 1111 1111 declined")
        assertFalse(sanitized.contains("4111 1111 1111 1111"))
        assertEquals("card [REDACTED] declined", sanitized)
    }

    @Test
    fun redact_masksSaudiIban() {
        val iban = "SA0380000000608010167519"
        val sanitized = AppLogRedactor.redact("transfer to $iban")
        assertFalse(sanitized.contains(iban))
    }

    @Test
    fun redact_masksLabeledOtp() {
        val sanitized = AppLogRedactor.redact("OTP: 482911 do not share")
        assertFalse(sanitized.contains("482911"))
    }

    @Test
    fun redact_preservesHarmlessIds() {
        val sanitized = AppLogRedactor.redact("rawSmsId=android-sms:42 tx=tx-100 ref=123456")
        assertEquals("rawSmsId=android-sms:42 tx=tx-100 ref=123456", sanitized)
    }

    @Test
    fun redact_preservesOrdinaryAmounts() {
        val sanitized = AppLogRedactor.redact("purchase 51.99 SAR on 2026-08-03")
        assertEquals("purchase 51.99 SAR on 2026-08-03", sanitized)
    }
}
