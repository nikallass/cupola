// Root build script. Plugins are declared here with `apply false` so that the
// version catalog resolves them once; each module applies what it needs.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Pure JVM modules must stay free of Android dependencies so they can be tested on
 * the desktop and later moved to KMP. `./gradlew check` fails if any Kotlin source in
 * these modules imports `android.*` or `androidx.*`.
 */
val pureJvmModules = setOf("core-dsp", "core-notation", "core-testdata")

subprojects {
    if (name !in pureJvmModules) return@subprojects

    val checkNoAndroidImports = tasks.register("checkNoAndroidImports") {
        group = "verification"
        description = "Fails if a pure JVM module imports android.* or androidx.*"
        val sources = layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") }
        inputs.files(sources)
        doLast {
            val forbidden = Regex("""^\s*import\s+androidx?\.""")
            val offenders = sources.files.filter { file -> file.useLines { lines -> lines.any(forbidden::containsMatchIn) } }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "Pure JVM module ':${project.name}' must not import android.*/androidx.*:\n" +
                        offenders.joinToString("\n") { "  " + it.relativeTo(projectDir) },
                )
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.named("check") { dependsOn(checkNoAndroidImports) }
    }
}
