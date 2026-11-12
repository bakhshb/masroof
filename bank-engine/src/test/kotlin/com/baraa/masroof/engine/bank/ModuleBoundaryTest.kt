package com.baraa.masroof.engine.bank

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Checks the actual source/build graph, including new modules added after this test. */
class ModuleBoundaryTest {
    private val root = File("..").canonicalFile
    private val projectDependency = Regex("project\\(\"([^\"]+)\"\\)")

    @Test fun removedMlImplementationCannotReturnToTheBankEngine() {
        val old = File(root, "bank-engine/src/main/kotlin/com/baraa/masroof/engine/bank/learning")
        assertFalse(old.walkTopDown().any { it.extension == "kt" }, "Duplicate ML implementation in bank-engine")
        assertFalse(File(root, "bank-engine/tools/train_family_model.py").exists(), "Duplicate training command")
    }

    @Test fun pureCoresImportOnlyJdkKotlinAndTheirOwnedContracts() {
        val common = listOf("java.", "kotlin.")
        mapOf(
            "ml-assist" to common + listOf("com.baraa.masroof.ml.", "com.baraa.masroof.analytics."),
            "finance-core" to common + listOf(
                "com.baraa.masroof.finance.",
                "com.baraa.masroof.core.money.",
                "com.baraa.masroof.domain.model.",
                "com.baraa.masroof.domain.ids.",
                "com.baraa.masroof.domain.period.",
                "com.baraa.masroof.domain.rules.",
                "com.baraa.masroof.domain.assembly.",
                "com.baraa.masroof.domain.matching.",
                "com.baraa.masroof.domain.statement.",
            ),
            "bank-engine" to common + listOf("com.baraa.masroof.engine.bank.", "com.baraa.masroof.finance.value."),
            "ledger" to common + listOf(
                "com.baraa.masroof.ledger.",
                "com.baraa.masroof.core.money.",
                "com.baraa.masroof.domain.model.",
                "com.baraa.masroof.domain.ids.",
                "com.baraa.masroof.domain.assembly.",
                "com.baraa.masroof.domain.matching.",
                "com.baraa.masroof.domain.rules.",
            ),
        ).forEach { (module, allowed) ->
            val sources = File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
            assertTrue(sources.isNotEmpty(), "Missing $module source tree")
            sources.forEach { file ->
                Regex("(?m)^import ([^\\s]+)").findAll(file.readText()).forEach { match ->
                    assertTrue(allowed.any { match.groupValues[1].startsWith(it) }, "${file.name} imports ${match.groupValues[1]}")
                }
                // Fully qualified calls must not bypass the import boundary. In particular,
                // bank-engine can use finance values, but never financial posting policies.
                Regex("\\bcom\\.baraa\\.masroof(?:\\.[A-Za-z_][A-Za-z_0-9]*|\\.\\*)+")
                    .findAll(file.readText()).forEach { match ->
                        assertTrue(allowed.any { match.value == it.removeSuffix(".") || match.value.startsWith(it) }, "${file.name} references ${match.value}")
                    }
                assertFalse(Regex("\\b(android|androidx)\\.").containsMatchIn(file.readText()), "Fully qualified Android API in ${file.name}")
                if (module == "bank-engine") assertFalse(Regex("com\\.baraa\\.masroof\\.lab\\.(ml|bank\\.learning)\\.").containsMatchIn(file.readText()), "ML implementation leaked into engine: ${file.name}")
                assertFalse(Regex("com\\.baraa\\.masroof\\.lab\\.(engine|copy)\\.").containsMatchIn(file.readText()), "Legacy interpreter reference in ${file.name}")
            }
        }
    }

    @Test fun pureModulesNeverDependOnAppAndModuleGraphHasNoCycles() {
        val buildFiles = listOf(File(root, "app/build.gradle.kts")) +
            listOf("bank-engine", "finance-core", "ml-assist", "ledger").map { File(root, "$it/build.gradle.kts") }
        val graph = buildFiles.associate { file ->
            val module = ":" + file.parentFile.relativeTo(root).invariantSeparatorsPath.replace('/', ':')
            module to projectDependency.findAll(file.readText()).map { it.groupValues[1] }.toSet()
        }
        graph.filterKeys { it != ":app" }.forEach { (module, deps) ->
            assertFalse(":app" in deps, "$module depends on production")
        }
        assertFalse(graph.keys.any { it in setOf(":lab:engine", ":lab:android") }, "Removed legacy modules must not return")
        assertEquals(emptySet<String>(), graph[":ml-assist"])
        assertEquals(emptySet<String>(), graph[":finance-core"])
        assertEquals(setOf(":finance-core"), graph[":bank-engine"])
        assertEquals(setOf(":finance-core"), graph[":ledger"])
        assertEquals(setOf(":ui-kit", ":finance-core", ":bank-engine", ":ml-assist", ":ledger"), graph[":app"])
        fun visit(module: String, ancestors: Set<String>) {
            assertFalse(module in ancestors, "Dependency cycle: $ancestors -> $module")
            graph[module].orEmpty().forEach { visit(it, ancestors + module) }
        }
        graph.keys.forEach { visit(it, emptySet()) }
    }

    @Test fun retiredInterpreterCannotShip() {
        val app = File(root, "app/src")
        assertFalse(app.walkTopDown().any { it.name in setOf("AlJaziraMessageParser.kt", "AlJaziraParsingPipeline.kt", "AlJaziraSmsAdapter.kt") })
        assertFalse(File(root, "settings.gradle.kts").readText().contains(":lab:"))
        assertFalse(File(root, "app/build.gradle.kts").readText().contains("generateProductionReplica"))
    }

    @Test fun referenceProfilesAndTestAdapterStayOutsideProductionSources() {
        val production = listOf("bank-engine", "app").flatMap { module ->
            File(root, "$module/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        }
        assertFalse(production.any {
            it.name in setOf("RuleLibrary.kt", "AlJaziraRuleProfile.kt", "NewEngineSmsAdapter.kt", "InterpretationTestAdapter.kt")
        }, "Reference vocabulary or adapter returned to production")
        for (name in listOf("RuleLibrary.kt", "AlJaziraRuleProfile.kt")) {
            assertTrue(File(root,
                "bank-engine/src/testFixtures/kotlin/com/baraa/masroof/engine/bank/rules/$name").isFile, "Independent reference missing: $name")
        }
    }
}
