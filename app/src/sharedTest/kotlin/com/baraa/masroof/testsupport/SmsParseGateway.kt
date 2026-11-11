package com.baraa.masroof.testsupport

import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput

/** Test-only parse seam that lets pipeline tests replace an adapter's parser. */
fun interface SmsParseGateway {
    fun parse(input: SmsParseInput): ParseResult
}
