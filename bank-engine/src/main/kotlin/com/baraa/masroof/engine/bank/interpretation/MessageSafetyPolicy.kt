package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.Evidence

/** Shared security gate: bank rules and learned patterns cannot override OTP evidence. */
internal fun otpEvidence(source: SourceText): List<Evidence> = (OTP_TERMS.flatMap { term ->
    source.phrase(term).findAll(source.text).toList()
} + GLUED_OTP.findAll(source.text)).map { source.evidence("security.otp", "family", it.range) }

// "OTP1234": the code may touch the word.
private val GLUED_OTP = CompiledPatterns.of("(?<![\\p{L}\\p{N}_])otp(?=[0-9])")

private val OTP_TERMS = listOf("otp", "one time password", "one-time password", "password", "passcode", "pin",
    "verification code", "security code", "authentication code", "رمز التحقق", "رمز تحقق", "رمز التوثيق",
    "رمز تاكيد", "رمز لمرة واحدة", "كلمة المرور", "كلمة مرور", "رمز التفعيل", "رمز سري",
    "الرقم السري لمرة واحدة", "الرقم السري لمره واحده", "الرمز السري", "رمز التأكيد", "رمز الدخول", "رمز المرور",
    "كلمة السر", "كود التحقق", "كود التفعيل", "activation code", "do not share this code", "لا تشارك الرمز")
// Approval/authorization and "transaction code" wording are left out on purpose: real purchase SMS carry them.

internal fun hasUnprovenExecution(source: SourceText) = NON_EXECUTION_TERMS.any { source.phrase(it).containsMatchIn(source.text) }

internal fun hasNoticeContext(source: SourceText) = NOTICE_CONTEXT_TERMS.any { source.phrase(it).containsMatchIn(source.text) }

internal fun hasQuotedContext(source: SourceText) = source.text.lineSequence().any {
    it.trimStart().let { line -> line.startsWith(">") || line.startsWith("```") || line.startsWith('"') || line.startsWith('«') || line.startsWith('“') }
}

private val NON_EXECUTION_TERMS = listOf("declined", "rejected", "unsuccessful", "failed", "pending", "request", "cancelled", "canceled", "not", "never", "لم", "لن", "لا", "غير", "رفض", "مرفوض", "مرفوضة", "طلب", "قيد التنفيذ", "تعذر", "الغاء")
private val NOTICE_CONTEXT_TERMS = listOf("statement", "كشف حساب", "beneficiary added", "اضافة مستفيد", "advertisement", "offer", "عرض", "minimum payment", "payment due date", "تاريخ الاستحقاق")
