import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    jacoco
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// JVM unit-test coverage. The app has no instrumentation tests (they need a
// device), so this measures exactly what can run on CI: pure Kotlin, repository
// logic, prompt/timing arithmetic and attachment policy.
val coverageModules = listOf("core", "code", "llm", "app")

subprojects {
    apply(plugin = "jacoco")
    tasks.withType<Test>().configureEach {
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Aggregated JaCoCo report for the JVM unit tests of all modules."

    dependsOn(coverageModules.map { ":$it:testDebugUnitTest" })

    val modules = coverageModules.map { project(it) }
    // AGP writes unit-test coverage to this path when the JaCoCo plugin is
    // applied; the plain `build/jacoco/*.exec` path is only for JVM projects.
    executionData.setFrom(
        modules.map {
            it.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
    sourceDirectories.setFrom(
        modules.map { files(it.file("src/main/kotlin"), it.file("src/main/java")) },
    )

    val classDirs = files(
        modules.map { module ->
            files(
                module.layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
                module.layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
            )
        },
    )
    classDirectories.setFrom(
        classDirs.files.map { dir ->
            fileTree(dir) {
                exclude(
                    "**/R.class",
                    "**/R$*.class",
                    "**/BuildConfig.*",
                    "**/Manifest*.*",
                    "**/*_Factory.*",
                    "**/*_Impl.*",
                    "**/*ComposableSingletons*.*",
                )
            }
        },
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
