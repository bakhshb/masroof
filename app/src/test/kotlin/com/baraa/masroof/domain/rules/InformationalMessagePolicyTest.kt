package com.baraa.masroof.domain.rules

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.MessageFamily
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InformationalMessagePolicyTest {
    @Test
    fun unknownWithoutAmountOrMoneyWording_isAutoIgnored() {
        assertTrue(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = null,
                smsBody = "اسم المستفيد : TEST\nحالة: غير نشط",
            ),
        )
    }

    @Test
    fun unknownWithParsedAmount_isNotAutoIgnored() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = Money.of("100", Currency.SAR),
                smsBody = "عملية غير معروفة",
            ),
        )
    }

    @Test
    fun unknownWithMoneyWordingButNoParsedAmount_staysForReview() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = null,
                smsBody = "عملية بمبلغ: 15000.00 SAR",
            ),
        )
    }

    @Test
    fun creditCardStatementWithZeroDue_isAutoIgnored() {
        assertTrue(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = Money.of("0.00", Currency.SAR),
                smsBody = """
                    بطاقة إئتمانية: إصدار كشف حساب
                    بطاقة: 7271 بطاقة إئتمانية
                    إجمالي المبلغ المستحق: SAR 0.00
                    تاريخ الاستحقاق: 07/09/2026
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun unknownPurchaseWithStatementDueWording_staysForReview() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = Money.of("89.50", Currency.SAR),
                smsBody = """
                    شراء عبر نقاط البيع
                    بمبلغ: 89.50 SAR
                    المبلغ المستحق: 1,250.00 SAR
                    تاريخ الاستحقاق: 25/08/2026
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun unknownTransferWithBeneficiaryNotice_staysForReview() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = Money.of("100.00", Currency.SAR),
                smsBody = """
                    حوالة صادرة
                    اسم المستفيد: TEST_PERSON
                    حالة: غير نشط
                    مبلغ: SAR 100.00
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun nonFinancialFamily_isAutoIgnored() {
        assertTrue(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.NON_FINANCIAL,
                parsedAmount = null,
                smsBody = "any",
            ),
        )
    }

    @Test
    fun unknownCreditCardRefundWithDueAmount_isNotAutoIgnored() {
        assertFalse(
            InformationalMessagePolicy.shouldAutoIgnore(
                messageFamily = MessageFamily.UNKNOWN,
                parsedAmount = Money.of("6.51", Currency.USD),
                smsBody = """
                    بطاقة إئتمانية: إسترداد مبلغ
                    بطاقة: Credit
                    رقم: 7271
                    من: CURSOR, AI POWERED IDE
                    مبلغ: 6.51 USD
                    رصيد: 11303.00 SAR
                    في: 18:23 17-08-2026
                    إجمالي المبلغ المستحق:7683.86 SAR
                """.trimIndent(),
            ),
        )
    }
}
