package com.baraa.masroof.domain.rules

import com.baraa.masroof.core.text.ArabicTextFolding

/**
 * Detects OTP / verification SMS that must never become financial transactions.
 *
 * Input is folded with [ArabicTextFolding] so callers may pass either a parser
 * comparison body or raw lowercase text.
 */
object OtpMessageHeuristics {
    private val OTP_KEYWORDS = listOf(
        "رمز التحقق",
        "otp",
        "one time password",
        "one-time password",
        "كلمة مرور",
        "كلمة المرور",
        "صالحة لمرة واحدة",
        "verification code",
        "do not share",
        "لا تشاركه",
        "رمز التفعيل",
        "لإضافة المستفيد",
    ).map(ArabicTextFolding::foldForComparison)

    fun isOtpMessage(comparisonBody: String): Boolean {
        val text = ArabicTextFolding.foldForComparison(comparisonBody)
        if (OTP_KEYWORDS.any { text.contains(it) }) {
            return true
        }
        return text.contains("code:") && text.contains("password")
    }
}
