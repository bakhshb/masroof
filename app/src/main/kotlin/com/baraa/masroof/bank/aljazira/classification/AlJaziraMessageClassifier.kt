package com.baraa.masroof.bank.aljazira.classification

import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.ACCOUNT_NOTICE
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.BALANCE_NOTICE
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.GENERIC
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.MOVEMENT
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.PRODUCT
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.SECURITY
import com.baraa.masroof.bank.aljazira.classification.AlJaziraClassificationSpecificity.STATEMENT
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
 *
 * Every rule in [RULES] is evaluated; [AlJaziraClassificationResolver] picks the unique
 * family in the highest [AlJaziraClassificationSpecificity] tier, or UNKNOWN (review)
 * when different families tie. Rule list order carries no precedence.
 */
class AlJaziraMessageClassifier {
    private val resolver = AlJaziraClassificationResolver(RULES)

    fun classify(sms: NormalizedSms): AlJaziraClassification = resolver.resolve(sms.comparisonBody)

    internal companion object {
        private const val PRODUCT_CONFIDENCE = 0.95
        private const val NOTICE_CONFIDENCE = 1.0

        /** Bank SMS uses both ا and إ spellings (e.g. استرداد vs إسترداد). */
        private val REFUND_AR_PATTERN = comparisonRegex("""[اأإآ]سترداد""")
        private val POS_TOKEN = Regex("""(?<![\p{L}])pos(?![\p{L}])""")
        private val BRACKETED_BANK = Regex("""\[[^\]]+\]""")

        private const val FEE_KEYWORD = "رسوم"

        val RULES: List<AlJaziraClassificationRule> = listOf(
            AlJaziraClassificationRule.of(
                id = "otp",
                specificity = SECURITY,
                matches = OtpMessageHeuristics::isOtpMessage,
                classify = ::otpClassification,
            ),
            notice(
                id = "login_notice",
                specificity = ACCOUNT_NOTICE,
                family = MessageFamily.NON_FINANCIAL,
            ) { it.containsComparison("تم تسجيل الدخول") },
            notice(
                id = "loyalty_points_notice",
                specificity = ACCOUNT_NOTICE,
                family = MessageFamily.NON_FINANCIAL,
            ) { text ->
                text.containsComparison("مكافآتي") ||
                    text.containsComparison("رصيد نقاطك") ||
                    text.containsComparison("برنامج مكاف")
            },
            notice(
                id = "beneficiary_notice",
                specificity = ACCOUNT_NOTICE,
                family = MessageFamily.NON_FINANCIAL,
            ) { text ->
                text.containsComparison("اسم المستفيد") ||
                    text.containsComparison("الاسم المختصر") ||
                    text.containsComparison("تم إضافة المستفيد") ||
                    text.containsComparison("إضافة مستفيد") ||
                    (text.containsComparison("حالة") && text.containsComparison("غير نشط"))
            },
            notice(
                id = "balance_notice",
                specificity = BALANCE_NOTICE,
                family = MessageFamily.BALANCE_NOTICE,
            ) { it.containsComparison("إشعار رصيد") },
            notice(
                id = "statement_notice",
                specificity = STATEMENT,
                family = MessageFamily.NON_FINANCIAL,
            ) { text ->
                text.containsComparison("كشف حساب") ||
                    (text.containsComparison("تاريخ الاستحقاق") && text.containsComparison("المبلغ المستحق"))
            },
            movement(
                id = "financing_installment",
                specificity = PRODUCT,
                family = MessageFamily.FINANCING_INSTALLMENT,
                direction = MoneyDirection.OUTGOING,
            ) { it.containsComparison("قسط تمويل") || it.containsComparison("خصم: قسط") },
            movement(
                id = "card_payment",
                specificity = PRODUCT,
                family = MessageFamily.CARD_PAYMENT,
                direction = MoneyDirection.OUTGOING,
            ) { it.containsComparison("سداد بطاقة") },
            movement(
                id = "card_settlement",
                specificity = PRODUCT,
                family = MessageFamily.CARD_PAYMENT,
                direction = MoneyDirection.OUTGOING,
            ) { !it.containsComparison("سداد بطاقة") && isCreditCardSettlement(it) },
            movement(
                id = "bill_payment",
                specificity = PRODUCT,
                family = MessageFamily.BILL_PAYMENT,
                direction = MoneyDirection.OUTGOING,
            ) { it.containsComparison("سداد فاتورة") || it.containsComparison("المفوتر") },
            movement(
                id = "refund",
                specificity = PRODUCT,
                family = MessageFamily.REFUND,
                direction = MoneyDirection.INCOMING,
            ) { it.containsComparison("refund") || REFUND_AR_PATTERN.containsMatchIn(it) },
            movement(
                id = "purchase_pos",
                specificity = MOVEMENT,
                rank = 1,
                family = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                purchaseChannel = PurchaseChannel.POS,
                matches = ::isPosPurchase,
            ),
            movement(
                id = "purchase_online",
                specificity = MOVEMENT,
                family = MessageFamily.PURCHASE,
                direction = MoneyDirection.OUTGOING,
                purchaseChannel = PurchaseChannel.ONLINE,
                matches = ::isOnlinePurchase,
            ),
            AlJaziraClassificationRule { text -> feeCandidate(text) },
            movement(
                id = "withdrawal",
                specificity = MOVEMENT,
                family = MessageFamily.WITHDRAWAL,
                direction = MoneyDirection.OUTGOING,
            ) { it.containsComparison("سحب نقدي") || it.containsComparison("withdrawal") },
            AlJaziraClassificationRule.of(
                id = "transfer_in",
                specificity = MOVEMENT,
                matches = { text ->
                    text.containsComparison("حوالة واردة") ||
                        text.containsComparison("حوالة مالية واردة") ||
                        text.containsComparison("incoming transfer")
                },
                classify = { text ->
                    AlJaziraClassification(
                        family = MessageFamily.TRANSFER_IN,
                        direction = MoneyDirection.INCOMING,
                        bankNetworkType = detectNetwork(text, incoming = true),
                        evidence = listOf("transfer_in"),
                        confidence = PRODUCT_CONFIDENCE,
                    )
                },
            ),
            AlJaziraClassificationRule.of(
                id = "transfer_out",
                specificity = MOVEMENT,
                matches = { text ->
                    text.containsComparison("حوالة صادرة") ||
                        text.containsComparison("حوالة مالية صادرة") ||
                        text.containsComparison("outgoing transfer")
                },
                classify = { text ->
                    AlJaziraClassification(
                        family = MessageFamily.TRANSFER_OUT,
                        direction = MoneyDirection.OUTGOING,
                        bankNetworkType = detectNetwork(text, incoming = false),
                        evidence = listOf("transfer_out"),
                        confidence = PRODUCT_CONFIDENCE,
                    )
                },
            ),
        )

        private fun notice(
            id: String,
            specificity: Int,
            family: MessageFamily,
            matches: (String) -> Boolean,
        ): AlJaziraClassificationRule = AlJaziraClassificationRule.of(
            id = id,
            specificity = specificity,
            matches = matches,
            classify = {
                AlJaziraClassification(family = family, evidence = listOf(id), confidence = NOTICE_CONFIDENCE)
            },
        )

        private fun movement(
            id: String,
            specificity: Int,
            family: MessageFamily,
            direction: MoneyDirection,
            rank: Int = 0,
            purchaseChannel: PurchaseChannel? = null,
            matches: (String) -> Boolean,
        ): AlJaziraClassificationRule {
            val classification = AlJaziraClassification(
                family = family,
                direction = direction,
                purchaseChannel = purchaseChannel,
                evidence = listOf(id),
                confidence = PRODUCT_CONFIDENCE,
            )
            return AlJaziraClassificationRule.of(
                id = id,
                specificity = specificity,
                rank = rank,
                matches = matches,
                classify = { classification },
            )
        }

        private fun otpClassification(text: String): AlJaziraClassification {
            val activation = text.containsComparison("رمز التفعيل")
            return AlJaziraClassification(
                family = if (activation || text.containsComparison("لإضافة المستفيد")) {
                    MessageFamily.NON_FINANCIAL
                } else {
                    MessageFamily.OTP
                },
                evidence = listOf(
                    when {
                        activation -> "activation_code"
                        text.containsComparison("one time password") ||
                            text.containsComparison("one-time password") -> "english_otp"
                        text.containsComparison("كلمة مرور") || text.containsComparison("كلمة المرور") ||
                            text.containsComparison("صالحة لمرة واحدة") -> "password_ar"
                        else -> "otp_indicator"
                    },
                ),
                confidence = NOTICE_CONFIDENCE,
            )
        }

        /**
         * «رسوم» in the title line names the message itself a fee debit (movement tier).
         * Anywhere else it is usually a fee line attached to another movement, so it only
         * wins when nothing more specific matched.
         */
        private fun feeCandidate(text: String): AlJaziraClassificationCandidate? {
            if (!text.containsComparison(FEE_KEYWORD)) return null
            val titled = titleLine(text).containsComparison(FEE_KEYWORD)
            return AlJaziraClassificationCandidate(
                ruleId = if (titled) "fee" else "fee_line",
                specificity = if (titled) MOVEMENT else GENERIC,
                rank = 0,
                classification = AlJaziraClassification(
                    family = MessageFamily.FEE,
                    direction = MoneyDirection.OUTGOING,
                    evidence = listOf("fee"),
                    confidence = PRODUCT_CONFIDENCE,
                ),
            )
        }

        private fun titleLine(text: String): String = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

        private fun isPosPurchase(text: String): Boolean {
            val hasPos = text.containsComparison("نقاط البيع") ||
                text.containsComparison("pos purchase") ||
                POS_TOKEN.containsMatchIn(text)
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
            if (text.containsComparison("البنك المرسل: بنك الجزيرة") ||
                text.containsComparison("البنك المرسل:بنك الجزيرة")
            ) {
                return BankNetworkType.INTRA_BANK
            }
            // External bank markers in brackets or named other banks
            if (BRACKETED_BANK.containsMatchIn(text)) {
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
    }
}
