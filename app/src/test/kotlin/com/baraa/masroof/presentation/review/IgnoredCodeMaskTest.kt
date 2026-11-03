package com.baraa.masroof.presentation.review

import com.baraa.masroof.domain.model.MessageFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class IgnoredCodeMaskTest {
    @Test
    fun ignoredUnknownMessage_hidesTheCodeButKeepsAmountsAndCards() {
        val body = "Your code is 482913. Card *1234 paid SAR 1500.00 and 2,500"
        assertEquals("Your code is ••••. Card *1234 paid SAR 1500.00 and 2,500",
            maskIgnoredCodes(body, MessageFamily.UNKNOWN, ignored = true))
    }

    @Test
    fun otpIsAlwaysMasked_andOpenRowsKeepTheirText() {
        assertEquals("رمز •••• للعملية", maskIgnoredCodes("رمز 4821 للعملية", MessageFamily.OTP, ignored = false))
        assertEquals("code 482913", maskIgnoredCodes("code 482913", MessageFamily.UNKNOWN, ignored = false))
    }
}
