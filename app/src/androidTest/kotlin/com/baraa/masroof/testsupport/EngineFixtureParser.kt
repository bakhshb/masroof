package com.baraa.masroof.testsupport

import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec

internal fun bundledConfiguration() = MessagePatternConfigurationCodec.decode(
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open("bank-patterns/BANK_ALJAZIRA.patterns.json").bufferedReader().use { it.readText() })
