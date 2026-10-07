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

    /**
     * Credit purchases that omit the word «ائتمان» still close with the card's
     * available balance. A bare "بطاقة" / "card" line is not enough.
     */
    private val AVAILABLE_BALANCE_MARKERS = listOf(
        "الرصيد المتاح",
        "available balance",
    )

    private val GENERIC_CARD_LINE = listOf(
        "بطاقة:",
        "card:",
    )

    fun isCreditCardSms(body: String): Boolean {
        val text = body.replace('\n', ' ')
        if (containsDebitMarkers(text)) return false
        if (CREDIT_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        if (DUE_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        if (hasAvailableBalance(text) && hasGenericCardLine(text)) return true
        return false
    }

    fun isDebitCardSms(body: String): Boolean {
        val text = body.replace('\n', ' ')
        return containsDebitMarkers(text)
    }

    private fun hasAvailableBalance(text: String): Boolean =
        AVAILABLE_BALANCE_MARKERS.any { text.containsComparison(it, ignoreCase = true) }

    private fun hasGenericCardLine(text: String): Boolean =
        GENERIC_CARD_LINE.any { text.containsComparison(it, ignoreCase = true) }

    private fun containsDebitMarkers(text: String): Boolean {
        if (text.containsComparison("خصمت من حساب", ignoreCase = true)) return true
        if (ARABIC_DEBIT_MARKERS.any { text.containsComparison(it, ignoreCase = true) }) return true
        if (text.containsComparison("debit card", ignoreCase = true)) return true
        return STANDALONE_MADA.containsMatchIn(text)
    }
}
