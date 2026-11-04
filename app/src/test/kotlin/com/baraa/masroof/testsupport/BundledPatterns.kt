package com.baraa.masroof.testsupport

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import java.io.File

/** The production pattern asset for [bankId], read from the app module working directory. */
internal fun bundledPatterns(bankId: String): MessagePatternConfiguration =
    MessagePatternConfigurationCodec.decode(File("src/main/assets/bank-patterns/$bankId.patterns.json").readText())
