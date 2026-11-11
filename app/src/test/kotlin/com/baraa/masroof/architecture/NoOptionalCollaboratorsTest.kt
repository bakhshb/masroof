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
 * [ALLOWED] are the listed exceptions:
 * - The pattern workflow switches decide what the pattern screen offers (`canPreviewPending`, `canApprove`,
 *   `canApplyPending`). Production always passes all three, but the androidTest journeys build the workflow
 *   without them and assert that screen. Making them non-null changes that UI and needs a device run.
 * - `LearningInsightsWorkflow.learning` is really null in production when a bank adapter override is set.
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

    @Test
    fun rule_flagsAParameterOnTheClassLineAndSeveralOnOneLine() {
        val source = listOf(
            "class A(private val a: SampleStore? = null, val b: SampleLoader?, val c: Clock?) {",
            "    val local: SampleService? = lookup()",
            "}",
        )
        assertEquals(
            listOf("A.kt:1 a: SampleStore?", "A.kt:1 b: SampleLoader?"),
            violations(listOf("A.kt" to source)),
        )
    }

    private fun violations(files: List<Pair<String, List<String>>>): List<String> =
        files.flatMap { (name, lines) ->
            lines.flatMapIndexed { index, line ->
                NULLABLE_PARAMETER.findAll(line).mapNotNull { match ->
                    val (parameter, type) = match.destructured
                    val simpleType = type.substringAfterLast('.')
                    if (COLLABORATOR_SUFFIXES.none(simpleType::endsWith)) return@mapNotNull null
                    if ("$name $parameter" in ALLOWED) return@mapNotNull null
                    "$name:${index + 1} $parameter: $simpleType?"
                }.toList()
            }
        }

    private companion object {
        val applicationFiles: List<File> = File("src/main/kotlin/com/baraa/masroof/application")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        /** `val name: Type?[ = null]` followed by `,` or `)` like a parameter; a local `val x: T? = expr` is not. */
        val NULLABLE_PARAMETER = Regex("""\bval (\w+): ([\w.]+)\?(?: = null)?(?=,|\))""")

        val COLLABORATOR_SUFFIXES = listOf(
            "Service", "Repository", "Workflow", "Policy", "Gateway", "Scheduler", "Recovery", "Updater",
            "Provider", "Discovery", "Processor", "UseCase", "Registry", "AppLog", "Probe", "Signal", "Store", "Loader",
        )

        val ALLOWED = setOf(
            "MessagePatternsWorkflow.kt pendingPreview",
            "MessagePatternsWorkflow.kt activation",
            "PendingPatternsWorkflow.kt batches",
            "LearningInsightsWorkflow.kt learning",
        )
    }
}
