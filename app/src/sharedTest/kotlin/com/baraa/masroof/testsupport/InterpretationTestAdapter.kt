package com.baraa.masroof.testsupport

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.InterpretationDraftMapper

import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.bank.SenderBankDetector
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.*

/** Explicit interpreter/reference tests only; ordinary integration fixtures use the bundled patterns. */
class InterpretationTestAdapter(private val interpret: (MessageInput) -> Interpretation) : BankSmsAdapter {
    override val bank = Bank.BANK_ALJAZIRA
    private val detector = aljaziraSenderDetector()
    override fun detect(sender: String, body: String) = detector.detect(sender, body)
    override fun parse(input: SmsParseInput): ParseResult {
        val message = MessageInput(input.rawSmsId,input.sender,input.body,input.receivedAt,durableSmsReceipt = true)
        return InterpretationDraftMapper().map(message,interpret(message),bank)
    }
}

/** The detector production builds from the bundled AlJazira senders. */
fun aljaziraSenderDetector() = SenderBankDetector(Bank.BANK_ALJAZIRA,
    listOf("AlJazira", "JaziraBank", "BankAlJazira", "AlJaziraBank", "بنك الجزيرة", "بنك الجزيره"), "بنك الجزيرة")
