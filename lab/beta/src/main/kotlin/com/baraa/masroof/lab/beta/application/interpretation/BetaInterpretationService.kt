package com.baraa.masroof.lab.beta.application.interpretation

import com.baraa.masroof.lab.bank.BankCatalog
import com.baraa.masroof.lab.bank.BankInterpreter
import com.baraa.masroof.lab.bank.Interpretation
import com.baraa.masroof.lab.bank.MessageInput

/** Explicit caller-supplied samples only: no inbox access, repository writes, corrections or posting. */
class BetaInterpretationService(private val candidate: BankInterpreter) {
    /** Beta runtime: exactly one interpreter call, without a reference/fallback. */
    fun interpret(input: MessageInput, catalog: BankCatalog): Interpretation = candidate.interpret(input, catalog)
    companion object {
        fun independent() = BetaInterpretationService(com.baraa.masroof.lab.bank.interpretation.RuleInterpreter(
            currentSchema()))

        /** Bank identities are configuration, not a second parser or inferred ownership. */
        fun currentSchema() = com.baraa.masroof.lab.bank.rules.RuleLibrary.current().let { schema ->
            schema.copy(network = schema.network.copy(standaloneDestinationBrackets = true, standaloneSourceBrackets = true, unidentifiedExternalBankMarkers=listOf("بنك","bank"), bankAliases = mapOf(
                "BANK_ALJAZIRA" to listOf("AlJazira", "Bank AlJazira", "AlJazira Bank", "بنك الجزيرة", "بنك الجزيره"),
                "BANK_D360" to listOf("بنك دال ثلاثمائة وستون", "D360 Bank", "D360 بنك"),
                "BANK_RIYAD" to listOf("بنك الرياض", "Riyad Bank"),
                "BANK_ANB" to listOf("البنك العربي الوطني", "Arab National Bank"),
                "BANK_SNB" to listOf("البنك الاهلي السعودي", "Saudi National Bank"),
            )))
        }


    }

}
