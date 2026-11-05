package com.baraa.masroof.analytics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class RecurringSeedTrainingTest {
    private fun row(id: String, owner: String, partition: String, label: RecurringLabel): String {
        val features = when (label) {
            RecurringLabel.WEEKLY -> listOf(1.0, .2, 0.0, 0.0, 1.0, 0.0, 1.0, .2)
            RecurringLabel.MONTHLY -> listOf(1.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0)
            RecurringLabel.NONE -> listOf(1.0, .5, 1.0, 1.0, 1.0, 1.0, 2.0, 1.0)
            RecurringLabel.YEARLY -> error("rules and human decisions only")
        }
        return listOf(id, owner, partition, label.name, "NONE").joinToString("\t") + "\t" + features.joinToString("\t")
    }
    @Test fun contributorLeakageIsRejectedBeforeFitting() {
        val directory = Files.createTempDirectory("recurring-seed").toFile()
        try {
            val corpus = directory.resolve("input.tsv").apply { writeText(row("a", "owner", "train", RecurringLabel.WEEKLY) + "\n" + row("b", "owner", "test", RecurringLabel.MONTHLY)) }
            assertThrows(IllegalArgumentException::class.java) { main(arrayOf(corpus.path, directory.resolve("model.json").path, directory.resolve("report.json").path)) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun reproducibleArtifactsNeedIndependentFinalTestAndTestNeverChoosesWeightsOrThreshold() {
        val directory = Files.createTempDirectory("recurring-seed").toFile()
        try {
            val corpus = directory.resolve("input.tsv")
            val rows = listOf("train", "validation", "test").flatMap { partition ->
                (0 until 90).map { row("$partition-$it", "owner-$partition", partition, RecurringLabel.entries[it % 3]) }
            }
            corpus.writeText(rows.joinToString("\n"))
            val model = directory.resolve("model.json")
            val report = directory.resolve("report.json")
            val args = arrayOf(corpus.path, model.path, report.path)
            main(args)
            val original = model.readText()
            assertTrue(original.contains("\"accepted\":true"))
            main(args + "--check")
            corpus.writeText(rows.map { line ->
                val columns = line.split('\t').toMutableList()
                if (columns[2] == "test") columns[3] = "NONE"
                columns.joinToString("\t")
            }.joinToString("\n"))
            main(args)
            val changed = model.readText()
            assertTrue(changed.contains("\"accepted\":false"))
            val learned = Regex("\\\"weights\\\":(.*?),\\\"threshold\\\":([0-9.]+)")
            assertEquals(learned.find(original)!!.value, learned.find(changed)!!.value)
        } finally { directory.deleteRecursively() }
    }
}
