package com.baraa.masroof.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent future screens from silently forking the shared design system. */
class DesignTokenBoundaryTest {
    @Test
    fun screenGeometryTypographyAndColors_areDefinedInTheTheme() {
        val root = File("src/main/kotlin/com/baraa/masroof/presentation")
        assertTrue("Presentation source directory must be available", root.isDirectory)
        val literal = Regex("\\b\\d+(?:\\.\\d+)?\\.(?:dp|sp)\\b|Color\\(0x|FontWeight\\.(?:Normal|Medium|SemiBold|Bold)|alpha\\s*=\\s*\\d+\\.\\d+f")
        val violations = root.walkTopDown().filter { it.extension == "kt" && "theme" !in it.relativeTo(root).invariantSeparatorsPath.split('/') }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line ->
                if (literal.containsMatchIn(line.substringBefore("//"))) "${file.relativeTo(root)}:${index + 1}" else null
            }.asSequence() }.toList()
        assertTrue("Define visual tokens in presentation/theme: $violations", violations.isEmpty())
    }
}
