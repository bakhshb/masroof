package com.baraa.masroof.testsupport

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.engine.bank.BankRuleSchema
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.parser.SmsParseGateway

private fun bundledConfiguration() = MessagePatternConfigurationCodec.decode(
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("bank-patterns/BANK_ALJAZIRA.patterns.json").bufferedReader().use { it.readText() })

/** Default integration setup follows production. Pass a profile only for an explicit reference test. */
fun engineFixtureAdapter(
    pipeline: SmsParseGateway? = null,
    detector: com.baraa.masroof.parsing.detector.BankDetector? = null,
    profile: BankRuleSchema? = null,
): BankSmsAdapter {
    val adapter = if (profile != null) {
        val engine = RuleInterpreter(profile)
        InterpretationTestAdapter { engine.interpretRouted(it, BankId(Bank.BANK_ALJAZIRA.id)) }
    } else {
        val configuration = bundledConfiguration()
        MessagePatternSmsAdapter(configuration.banks.single(), PatternFactInterpreter(configuration))
    }
    // Narrow pipeline tests may replace parsing/detection; production preservation journeys use AppContainer.
    return object : BankSmsAdapter by adapter {
        override fun detect(sender: String, body: String) = detector?.detect(sender, body) ?: adapter.detect(sender, body)
        override fun parse(input: com.baraa.masroof.parsing.model.SmsParseInput) = pipeline?.parse(input) ?: adapter.parse(input)
    }
}
