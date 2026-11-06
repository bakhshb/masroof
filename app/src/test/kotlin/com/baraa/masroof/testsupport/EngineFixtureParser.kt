package com.baraa.masroof.testsupport

import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.parser.SmsParseGateway

internal fun bundledConfiguration() = bundledPatterns("BANK_ALJAZIRA")

class EngineFixtureParser(
    private val validator: com.baraa.masroof.parsing.validator.ParsedEventValidator = com.baraa.masroof.parsing.validator.DefaultParsedEventValidator(),
) : SmsParseGateway {
    private val engine = PatternFactInterpreter(bundledConfiguration())
    override fun parse(input: com.baraa.masroof.parsing.model.SmsParseInput): com.baraa.masroof.parsing.model.ParseResult {
        val message = com.baraa.masroof.engine.bank.MessageInput(input.rawSmsId, input.sender, input.body, input.receivedAt, durableSmsReceipt = true)
        return InterpretationDraftMapper(validator).map(message, engine.interpretRouted(message, BankId(Bank.BANK_ALJAZIRA.id)), Bank.BANK_ALJAZIRA)
    }
}
