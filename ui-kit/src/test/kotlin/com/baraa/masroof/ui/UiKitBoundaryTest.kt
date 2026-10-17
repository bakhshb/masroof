package com.baraa.masroof.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiKitBoundaryTest {
    @Test fun displayModuleCannotReachBusinessDataOrOtherProjects() {
        val build = File("build.gradle.kts").readText()
        assertFalse("UI kit must not depend on any application project", build.contains("project("))
        val forbidden = Regex("com\\.baraa\\.masroof\\.(?:application|data|domain|bank|parsing|sms|lab|presentation|core)\\.|androidx\\.room|android\\.database")
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        sources.forEach { file ->
            assertFalse("Display-only dependency violated in $file", forbidden.containsMatchIn(file.readText()))
        }
        val literals = Regex("\\b\\d+(?:\\.\\d+)?\\.(?:dp|sp)\\b|Color\\(0x|FontWeight\\.(?:Normal|Medium|SemiBold|Bold)")
        sources.filterNot { "/theme/" in it.invariantSeparatorsPath }.forEach { file ->
            assertFalse("Shared components must use shared design tokens: $file", literals.containsMatchIn(file.readText()))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("Display module must not request permissions", manifest.contains("uses-permission"))
        assertFalse("Display module must not register an entry point", manifest.contains("<application"))
    }
}
