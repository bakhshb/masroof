package com.baraa.masroof.integration.replica

import com.baraa.masroof.testsupport.testdataFile

internal fun referenceFixtureText(name: String): String =
    listOf("bank_aljazira", "engine").flatMap { testdataFile(it).walkTopDown().filter { file -> file.name == "$name.json" }.toList() }
        .single().readText()
