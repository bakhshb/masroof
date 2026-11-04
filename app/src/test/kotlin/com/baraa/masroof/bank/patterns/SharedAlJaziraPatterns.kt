package com.baraa.masroof.bank.patterns

import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.testsupport.bundledPatterns

internal fun bundledAlJaziraPatternAdapter(): MessagePatternSmsAdapter {
    val configuration = bundledPatterns("BANK_ALJAZIRA")
    return MessagePatternSmsAdapter(configuration.banks.single(), PatternFactInterpreter(configuration))
}
