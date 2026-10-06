package com.baraa.masroof.parsing.parser

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.NormalizedSms
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput

/**
 * Bank-specific SMS parser adapter.
 *
 * Invoked only after bank routing has selected this bank; the route is authoritative,
 * so parsers must not re-run sender detection. Every produced event carries [bank].
 *
 * Stops at structured parse facts ([ParseResult] / [com.baraa.masroof.domain.model.ParsedEvent]).
 * Must not resolve ownership, self-transfer, expense/income, or final financial treatment.
 */
interface BankMessageParser {
    val bank: Bank

    fun parse(input: SmsParseInput, normalized: NormalizedSms): ParseResult
}
