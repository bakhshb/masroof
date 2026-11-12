package com.baraa.masroof.parsing.model

/**
 * Card SMS channel inferred at parse time from message wording.
 *
 * Bank adapters populate this on [ParsedEventDetails]; dashboard code must not
 * re-parse raw SMS bodies for credit vs debit vs statement classification.
 */
enum class CardSmsChannel {
    CREDIT,
    DEBIT,
    STATEMENT,
}

fun ParsedEventDetails.isCreditCardSms(): Boolean =
    cardSmsChannel == CardSmsChannel.CREDIT || cardSmsChannel == CardSmsChannel.STATEMENT

fun ParsedEventDetails.isDebitCardSms(): Boolean =
    cardSmsChannel == CardSmsChannel.DEBIT

fun ParsedEventDetails.isStatementSms(): Boolean =
    cardSmsChannel == CardSmsChannel.STATEMENT
