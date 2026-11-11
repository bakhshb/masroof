package com.baraa.masroof.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A service, repository or other collaborator under `application/` is never optional: a constructor
 * parameter typed `X?` hides a branch that production never takes. Tests pass a no-op double instead
 * (`NoOp*`, `Empty*`, `testsupport/`). Lambdas, clocks, files and plain values may stay nullable.
 *
 * A collaborator is a constructor parameter whose type name ends in one of [COLLABORATOR_SUFFIXES].
 * [CAPABILITY_SWITCHES] are the listed exceptions. The pattern workflows switch what the pattern screen
 * offers (`canPreviewPending`, `canApprove`, `canApplyPending`). The provenance workflow's absence is shown
 * as no provenance, while a real one over empty stores reports `UNAVAILABLE`, which would change what the
 * loaders' tests prove.
 */
class NoOptionalCollaboratorsTest {

    @Test
    fun applicationConstructors_declareNoNullableCollaborator() {
        assertTrue("Expected Kotlin sources", applicationFiles.isNotEmpty())
        assertEquals(emptyList<String>(), violations(applicationFiles.map { it.name to it.readLines() }))
    }

    @Test
    fun rule_flagsANullableServiceParameter() {
        val source = listOf(
            "class Sample(",
            "    private val repo: SampleRepository,",
            "    private val log: AppLog? = null,",
            "    private val clock: Clock? = null,",
            ") {",
        )
        assertEquals(listOf("Sample.kt:3 log: AppLog?"), violations(listOf("Sample.kt" to source)))
    }

    private fun violations(files: List<Pair<String, List<String>>>): List<String> =
        files.flatMap { (name, lines) ->
            lines.mapIndexedNotNull { index, line ->
                val match = NULLABLE_PARAMETER.matchEntire(line) ?: return@mapIndexedNotNull null
                val (parameter, type) = match.destructured
                val simpleType = type.substringAfterLast('.')
                if (COLLABORATOR_SUFFIXES.none(simpleType::endsWith)) return@mapIndexedNotNull null
                if ("$name $parameter" in CAPABILITY_SWITCHES) return@mapIndexedNotNull null
                "$name:${index + 1} $parameter: $simpleType?"
            }
        }

    private companion object {
        val applicationFiles: List<File> = File("src/main/kotlin/com/baraa/masroof/application")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        /** `[private] val name: Type?[ = null]` ending the line like a constructor parameter does. */
        val NULLABLE_PARAMETER = Regex("""\s*(?:private |internal )?val (\w+): ([\w.]+)\?(?: = null)?(?:,|\)(?: \{)?)""")

        val COLLABORATOR_SUFFIXES = listOf(
            "Service", "Repository", "Workflow", "Policy", "Gateway", "Scheduler", "Recovery", "Updater",
            "Provider", "Discovery", "Processor", "UseCase", "Registry", "AppLog", "Probe",
        )

        val CAPABILITY_SWITCHES = setOf(
            "MessagePatternsWorkflow.kt pendingPreview",
            "MessagePatternsWorkflow.kt activation",
            "PendingPatternsWorkflow.kt batches",
            "ReviewDetailLoader.kt provenanceWorkflow",
            "TransactionSmsEvidenceLoader.kt provenanceWorkflow",
        )
    }
}
