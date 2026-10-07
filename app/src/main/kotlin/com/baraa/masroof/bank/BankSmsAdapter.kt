package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput

/**
 * Bank-specific SMS detection and parsing boundary.
 *
 * Each bank packages its detector and parse pipeline behind one adapter.
 * [detect] is evaluated only by [BankSmsRegistry]; once the registry selects this
 * adapter, [parse] must trust that route (no second sender check) and every
 * produced event must carry [bank].
 */
interface BankSmsAdapter {
    val bank: Bank

    fun detect(sender: String, body: String): BankDetectionResult

    fun parse(input: SmsParseInput): ParseResult
}
