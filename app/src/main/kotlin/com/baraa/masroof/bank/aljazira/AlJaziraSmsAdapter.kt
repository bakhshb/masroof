package com.baraa.masroof.bank.aljazira

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.detector.BankDetector
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import com.baraa.masroof.parsing.parser.SmsParseGateway
import java.time.ZoneId

/**
 * Bank AlJazira SMS adapter wrapping the existing detector and parse pipeline.
 *
 * [detect] is the only AlJazira sender check; [parse] runs after routing and does
 * not re-detect.
 */
class AlJaziraSmsAdapter(
    private val detector: BankDetector = AlJaziraBankDetector(),
    private val pipeline: SmsParseGateway = AlJaziraParsingPipeline(),
) : BankSmsAdapter {
    override val bank: Bank = Bank.BANK_ALJAZIRA

    override val transactionZone: ZoneId = ZoneId.of("Asia/Riyadh")

    override fun detect(sender: String, body: String): BankDetectionResult =
        detector.detect(sender, body)

    override fun parse(input: SmsParseInput): ParseResult =
        pipeline.parse(input)
}
