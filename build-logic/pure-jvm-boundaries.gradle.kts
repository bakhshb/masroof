import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// Applied only to the new pure contracts. Test tools may depend on coroutines/JUnit;
// shipped compile/runtime dependencies may contain only Kotlin/JDK support libraries.
val verifyPureJvmDependencies = tasks.register("verifyPureJvmDependencies") {
    doLast {
        listOf("compileClasspath", "runtimeClasspath").forEach { name ->
            configurations.getByName(name).incoming.resolutionResult.allComponents.forEach { component ->
                (component.id as? ModuleComponentIdentifier)?.let { module ->
                    check(module.group in setOf("org.jetbrains.kotlin", "org.jetbrains")) {
                        "${project.path}:$name has a forbidden external dependency: $module"
                    }
                }
            }
        }
    }
}
// The pure modules use Jupiter; Android and Robolectric retain their native runner.
tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(verifyPureJvmDependencies)
}
