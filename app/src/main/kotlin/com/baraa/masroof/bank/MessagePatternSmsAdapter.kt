package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.SmsParseInput

/** A bank-scoped immutable configuration snapshot; registry detection owns sender authority. */
class MessagePatternSmsAdapter(definition: BankMessagePatterns, private val interpreter: PatternFactInterpreter,
    private val interpret: ((MessageInput, PatternFactInterpreter) -> com.baraa.masroof.engine.bank.Interpretation)? = null) : BankSmsAdapter {
    override val bank = Bank(definition.bankId)
    val configurationFingerprint: String get() = interpreter.fingerprint
    private val detector = SenderBankDetector(bank, definition.senders)
    override fun detect(sender: String, body: String): BankDetectionResult = detector.detect(sender, body)

    override fun parse(input: SmsParseInput) = MessageInput(input.rawSmsId, input.sender, input.body, input.receivedAt, true).let {
        InterpretationDraftMapper().map(it, interpret?.invoke(it, interpreter) ?: interpreter.interpretRouted(it, BankId(bank.id)), bank)
    }
}
