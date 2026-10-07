package com.baraa.masroof.domain.rules

import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParsedEvent

/**
 * Families the parser has already decided are not financial movement.
 *
 * Reconciliation and review use this parsed family only. SMS wording is not
 * read here: a second text pass could hide a purchase or transfer that the
 * classifier left as [MessageFamily.UNKNOWN] for review.
 */
object InformationalMessagePolicy {
    fun shouldAutoIgnore(event: ParsedEvent): Boolean =
        shouldAutoIgnore(event.messageFamily)

    fun shouldAutoIgnore(messageFamily: MessageFamily?): Boolean =
        when (messageFamily) {
            MessageFamily.OTP,
            MessageFamily.NON_FINANCIAL,
            MessageFamily.BALANCE_NOTICE,
            -> true

            else -> false
        }
}
