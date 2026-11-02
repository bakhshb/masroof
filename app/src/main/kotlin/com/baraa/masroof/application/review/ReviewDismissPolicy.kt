package com.baraa.masroof.application.review

import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.rules.InformationalMessagePolicy

/** Whether a review may offer "not financial": an informational reason or the parsed family says so. */
object ReviewDismissPolicy {
    fun offersNonFinancialDismiss(messageFamily: MessageFamily?, reasons: List<String>): Boolean =
        reasons.any { it == "non_financial_or_informational_message" } ||
            InformationalMessagePolicy.shouldAutoIgnore(messageFamily)
}
