package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.domain.rules.OtpMessageHeuristics
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.parsing.model.NormalizedSms
import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.parsing.normalizer.containsComparison

data class AlJaziraClassification(
    val family: MessageFamily,
    val direction: MoneyDirection? = null,
    val purchaseChannel: PurchaseChannel? = null,
    val bankNetworkType: BankNetworkType? = null,
    val evidence: List<String> = emptyList(),
    val confidence: Double = 0.9,
)

/**
 * Evidence-based family classification from fixture-supported aliases only.
 */
class AlJaziraMessageClassifier {
    fun classify(sms: NormalizedSms): AlJaziraClassification {
        val text = sms.comparisonBody

        when {
            OtpMessageHeuristics.isOtpMessage(text) ->
                return AlJaziraClassification(
                    family = if (text.containsComparison("رمز التفعيل") || text.containsComparison("لإضافة المستفيد")) {
                        MessageFamily.NON_FINANCIAL
                    } else {
                        MessageFamily.OTP
                    },
                    evidence = listOf(
                        when {
                            text.containsComparison("رمز التفعيل") -> "activation_code"
                            text.containsComparison("one time password") || text.containsComparison("one-time password") ->
                                "english_otp"
                            text.containsComparison("كلمة مرور") || text.containsComparison("كلمة المرور") ||
                                text.containsComparison("صالحة لمرة واحدة") -> "password_ar"
                            else -> "otp_indicator"
                        },
                    ),
                    confidence = 1.0,
                )

            text.containsComparison("تم تسجيل الدخول") ->
                return AlJaziraClassification(
                    family = MessageFamily.NON_FINANCIAL,
                    evidence = listOf("login_notice"),
                    confidence = 1.0,
                )

            text.containsComparison("مكافآتي") ||
                text.containsComparison("رصيد نقاطك") ||
                text.containsComparison("برنامج مكاف") ->
                return AlJaziraClassification(
                    family = MessageFamily.NON_FINANCIAL,
                    evidence = listOf("loyalty_points_notice"),
                    confidence = 1.0,
                )

            text.containsComparison("اسم المستفيد") ||
                text.containsComparison("الاسم المختصر") ||
                text.containsComparison("تم إضافة المستفيد") ||
                text.containsComparison("إضافة مستفيد") ||
                (text.containsComparison("حالة") && text.containsComparison("غير نشط")) ->
                return AlJaziraClassification(
                    family = MessageFamily.NON_FINANCIAL,
                    evidence = listOf("beneficiary_notice"),
                    confidence = 1.0,
                )

            text.containsComparison("إشعار رصيد") || text.containsComparison("اشعار رصيد") ->
                return AlJaziraClassification(
                    family = MessageFamily.BALANCE_NOTICE,
                    evidence = listOf("balance_notice"),
                    confidence = 1.0,
                )

            text.containsComparison("إصدار كشف حساب") ||
                text.containsComparison("كشف حساب") ||
                (text.containsComparison("تاريخ الاستحقاق") && text.containsComparison("المبلغ المستحق")) ->
                return AlJaziraClassification(
                    family = MessageFamily.NON_FINANCIAL,
                    evidence = listOf("statement_notice"),
                    confidence = 1.0,
                )

            text.containsComparison("قسط تمويل") || text.containsComparison("خصم: قسط") ->
                return AlJaziraClassification(
                    family = MessageFamily.FINANCING_INSTALLMENT,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf("financing_installment"),
                    confidence = 0.95,
                )

            text.containsComparison("سداد بطاقة") || isCreditCardSettlement(text) ->
                return AlJaziraClassification(
                    family = MessageFamily.CARD_PAYMENT,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf(
                        if (text.containsComparison("سداد بطاقة")) "card_payment" else "card_settlement",
                    ),
                    confidence = 0.95,
                )

            text.containsComparison("سداد فاتورة") || text.containsComparison("المفوتر") ->
                return AlJaziraClassification(
                    family = MessageFamily.BILL_PAYMENT,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf("bill_payment"),
                    confidence = 0.95,
                )

            isRefund(text) ->
                return AlJaziraClassification(
                    family = MessageFamily.REFUND,
                    direction = MoneyDirection.INCOMING,
                    evidence = listOf("refund"),
                    confidence = 0.95,
                )

            isPosPurchase(text) ->
                return AlJaziraClassification(
                    family = MessageFamily.PURCHASE,
                    direction = MoneyDirection.OUTGOING,
                    purchaseChannel = PurchaseChannel.POS,
                    evidence = listOf("purchase_pos"),
                    confidence = 0.95,
                )

            isOnlinePurchase(text) ->
                return AlJaziraClassification(
                    family = MessageFamily.PURCHASE,
                    direction = MoneyDirection.OUTGOING,
                    purchaseChannel = PurchaseChannel.ONLINE,
                    evidence = listOf("purchase_online"),
                    confidence = 0.95,
                )

            text.containsComparison("رسوم") ->
                return AlJaziraClassification(
                    family = MessageFamily.FEE,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf("fee"),
                    confidence = 0.95,
                )

            text.containsComparison("سحب نقدي") || text.containsComparison("withdrawal") ->
                return AlJaziraClassification(
                    family = MessageFamily.WITHDRAWAL,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf("withdrawal"),
                    confidence = 0.95,
                )

            text.containsComparison("حوالة واردة") ||
                text.containsComparison("حوالة مالية واردة") ||
                text.containsComparison("incoming transfer") ->
                return AlJaziraClassification(
                    family = MessageFamily.TRANSFER_IN,
                    direction = MoneyDirection.INCOMING,
                    bankNetworkType = detectNetwork(text, incoming = true),
                    evidence = listOf("transfer_in"),
                    confidence = 0.95,
                )

            text.containsComparison("حوالة صادرة") ||
                text.containsComparison("حوالة مالية صادرة") ||
                text.containsComparison("outgoing transfer") ->
                return AlJaziraClassification(
                    family = MessageFamily.TRANSFER_OUT,
                    direction = MoneyDirection.OUTGOING,
                    bankNetworkType = detectNetwork(text, incoming = false),
                    evidence = listOf("transfer_out"),
                    confidence = 0.95,
                )

            else ->
                return AlJaziraClassification(
                    family = MessageFamily.UNKNOWN,
                    evidence = listOf("unrecognized_aljazira_format"),
                    confidence = 0.3,
                )
        }
    }

    private fun isRefund(text: String): Boolean =
        text.containsComparison("refund") || REFUND_AR_PATTERN.containsMatchIn(text)

    private fun isPosPurchase(text: String): Boolean {
        val hasPos = text.containsComparison("نقاط البيع") ||
            text.containsComparison("pos purchase") ||
            Regex("""(?<![\p{L}])pos(?![\p{L}])""").containsMatchIn(text)
        val hasPurchase = text.containsComparison("شراء") || text.containsComparison("purchase")
        return hasPos && (hasPurchase || text.containsComparison("pos purchase"))
    }

    private fun isOnlinePurchase(text: String): Boolean {
        if (OtpMessageHeuristics.isOtpMessage(text)) return false
        return text.containsComparison("شراء عبر الانترنت") ||
            text.containsComparison("شراء من الانترنت") ||
            text.containsComparison("online purchase") ||
            text.containsComparison("internet purchase")
    }

    private fun detectNetwork(text: String, incoming: Boolean): BankNetworkType {
        if (text.containsComparison("داخلية") || text.containsComparison("حسابك الجاري")) {
            return BankNetworkType.INTRA_BANK
        }
        if (text.containsComparison("البنك المرسل: بنك الجزيرة") || text.containsComparison("البنك المرسل:بنك الجزيرة")) {
            return BankNetworkType.INTRA_BANK
        }
        // External bank markers in brackets or named other banks
        if (Regex("""\[[^\]]+\]""").containsMatchIn(text)) {
            return BankNetworkType.INTER_BANK
        }
        if (text.containsComparison("عبر:") && !text.containsComparison("بنك الجزيرة")) {
            // e.g. عبر: بنك الرياض
            if (text.containsComparison("بنك") && !text.containsComparison("عبر: بنك الجزيرة")) {
                return BankNetworkType.INTER_BANK
            }
        }
        if (text.containsComparison("البنك المرسل:") && !text.containsComparison("البنك المرسل: بنك الجزيرة")) {
            return BankNetworkType.INTER_BANK
        }
        if (incoming && text.containsComparison("محلية") && text.containsComparison("بنك الرياض")) {
            return BankNetworkType.INTER_BANK
        }
        return BankNetworkType.UNKNOWN
    }

    /** e.g. «بطاقة إئتمانية: تسديد» — settlement from account to credit card. */
    private fun isCreditCardSettlement(text: String): Boolean {
        if (!text.containsComparison("تسديد")) return false
        return text.containsComparison("بطاقة ائتمان", ignoreCase = true) ||
            text.containsComparison("بطاقة إئتمان", ignoreCase = true) ||
            text.containsComparison("credit card", ignoreCase = true)
    }

    companion object {
        /** Bank SMS uses both ا and إ spellings (e.g. استرداد vs إسترداد). */
        private val REFUND_AR_PATTERN = comparisonRegex("""[اأإآ]سترداد""")
    }
}
