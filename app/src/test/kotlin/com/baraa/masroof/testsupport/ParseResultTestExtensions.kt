package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.ParsedEventDetails

/** The event a parse produced, or null for Invalid/Unsupported. */
internal fun ParseResult.eventOrNull(): ParsedEvent? = when (this) {
    is ParseResult.Success -> event
    is ParseResult.Partial -> event
    is ParseResult.ReviewRequired -> event
    is ParseResult.NonFinancial -> event
    is ParseResult.Invalid, is ParseResult.Unsupported -> null
}

/** The details a parse produced, or null for Invalid/Unsupported. */
internal fun ParseResult.detailsOrNull(): ParsedEventDetails? = when (this) {
    is ParseResult.Success -> details
    is ParseResult.Partial -> details
    is ParseResult.ReviewRequired -> details
    is ParseResult.NonFinancial -> details
    is ParseResult.Invalid, is ParseResult.Unsupported -> null
}
