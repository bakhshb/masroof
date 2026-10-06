package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.parsing.normalizer.containsComparison

/**
 * Distinguishes credit-card SMS from debit (mada) and current-account purchase SMS.
 *
 * AlJazira credit-card purchase messages carry available/due balances; debit purchases
 * debit the linked account ("خصمت من حساب") and must not feed credit-card snapshots.
 */
object CreditCardMessageHeuristics {
    private val ARABIC_DEBIT_MARKERS = listOf(
        "بطاقة مدى",
        "بطاقة مدي",
    )

    /** Standalone English "mada" only — avoids false positives inside words like "Ramadan". */
    private val STANDALONE_MADA = Regex("(?<![a-zA-Z])mada(?![a-zA-Z])", RegexOption.IGNORE_CASE)

    private val CREDIT_MARKERS = listOf(
        "بطاقة ائتمان",
        "بطاقة إئتمان",
        "credit card",
    )

    private val DUE_MARKERS = listOf(
        "المبلغ المستحق",
        "due amount",
    )

    fun isCreditCardSms(body: String): Boolean {
        val text = body.replace('\n', ' ')
        if (containsDebitMarkers(text)) return false
        if (CREDIT_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        if (DUE_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        return false
    }

    fun isDebitCardSms(body: String): Boolean {
        val text = body.replace('\n', ' ')
        return containsDebitMarkers(text)
    }

    private fun containsDebitMarkers(text: String): Boolean {
        if (text.containsComparison("خصمت من حساب", ignoreCase = true)) return true
        if (ARABIC_DEBIT_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        if (text.containsComparison("debit card", ignoreCase = true)) return true
        return STANDALONE_MADA.containsMatchIn(text)
    }
}
