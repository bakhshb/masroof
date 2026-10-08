package com.baraa.masroof.application.logging

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class IngestionLoggingPrivacyTest {
    @Test
    fun ingestionAndSmsWorkers_doNotInterpolateSmsBodyIntoLogs() {
        val sourceRoot = File("src/main/kotlin/com/baraa/masroof")
        val watched = listOf(
            sourceRoot.resolve("application/ingestion"),
            sourceRoot.resolve("application/sms"),
        ).flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        val logLine = Regex("""appLogService\.(info|warn|error)\(""")
        for (file in watched) {
            file.readLines().forEachIndexed { index, line ->
                if (!logLine.containsMatchIn(line)) return@forEachIndexed
                assertFalse(
                    "${file.path}:${index + 1} must not log SMS body text",
                    ".body" in line || "rawSms.body" in line || "sms.body" in line,
                )
            }
        }
    }
}
