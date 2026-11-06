package com.baraa.masroof.testsupport

import java.io.File

/** A file or folder under `src/test/resources/testdata`, run from the app module or the repository root. */
internal fun testdataFile(path: String): File =
    listOf(File("src/test/resources/testdata/$path"), File("app/src/test/resources/testdata/$path"))
        .firstOrNull { it.exists() } ?: error("Could not locate testdata/$path")
