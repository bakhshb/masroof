package com.baraa.masroof.testsupport

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import java.util.concurrent.atomic.AtomicInteger

/** Claims every sender, so next to a real adapter the route is Ambiguous. Counts parse calls. */
internal class LookalikeBankAdapter : BankSmsAdapter {
    val parseCalls = AtomicInteger(0)
    override val bank: Bank = Bank("LOOKALIKE_BANK")

    override fun detect(sender: String, body: String): BankDetectionResult =
        BankDetectionResult.Detected(bank = bank, confidence = Confidence(1.0), evidence = listOf("sender:$sender"))

    override fun parse(input: SmsParseInput): ParseResult {
        parseCalls.incrementAndGet()
        return ParseResult.Unsupported(reason = "lookalike")
    }
}
