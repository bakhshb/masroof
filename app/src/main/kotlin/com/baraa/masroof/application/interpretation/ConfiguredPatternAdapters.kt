package com.baraa.masroof.application.interpretation

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter

/** Each routing attempt sees one complete document. */
class ConfiguredPatternAdapters(private val store: MessagePatternStore,
    private val assistedBanks: (MessagePatternConfiguration) -> Set<String> = { emptySet() },
    private val assistedInterpret: ((MessageInput, PatternFactInterpreter, String) -> Interpretation)? = null) {
    fun snapshot(): List<BankSmsAdapter> {
        val configuration = store.load()
        val interpreter = PatternFactInterpreter.of(configuration)
        val assisted = assistedBanks(configuration)
        return configuration.banks.sortedBy { it.bankId }.map { bank ->
            MessagePatternSmsAdapter(bank, interpreter, assistedInterpret?.takeIf { bank.bankId in assisted }
                ?.let { interpret -> { input, snapshot -> interpret(input, snapshot, bank.bankId) } })
        }
    }
}
