package com.baraa.masroof.application.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(watched.isNotEmpty())
        for (file in watched) {
            for (call in loggingCalls(file.readText())) {
                assertFalse(
                    "${file.path} logs SMS body:\n$call",
                    BODY_INTERPOLATION.containsMatchIn(call),
                )
            }
        }
    }

    @Test
    fun loggingCallScanner_includesArgumentsOnLaterLines() {
        val source = """
            appLogService.info(
                AppLogCategories.INGEST,
                "saw ${'$'}{rawSms.body}",
            )
        """.trimIndent()
        val calls = loggingCalls(source)
        assertTrue(calls.single().contains("rawSms.body"))
        assertTrue(BODY_INTERPOLATION.containsMatchIn(calls.single()))
    }

    private fun loggingCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < source.length) {
            val match = LOG_CALL.find(source, searchFrom) ?: break
            var depth = 1
            var index = match.range.last + 1
            while (index < source.length && depth > 0) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                index++
            }
            calls += source.substring(match.range.first, index)
            searchFrom = index
        }
        return calls
    }

    private companion object {
        val LOG_CALL: Regex = Regex("""appLogService\??\.(info|warn|error)\(""")
        val BODY_INTERPOLATION: Regex = Regex("""\.body\b""")
    }
}
