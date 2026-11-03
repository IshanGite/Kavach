plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    // Warnings are bugs in a safety product.
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // Live-LLM evals need a running Ollama; they run via :core:llmEval, never in CI.
        excludeTags("live-llm")
    }
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The eval suite writes its scorecard here (also uploaded by CI).
    systemProperty("kavach.reportDir", layout.buildDirectory.dir("reports/kavach-eval").get().asFile.absolutePath)
    // RepoSecurityTest scans files outside this module (manifest, .gitignore, app sources). Declare them as inputs so a change
    // there invalidates Gradle's up-to-date check; otherwise the security gates could be silently skipped.
    inputs.files(rootProject.fileTree(rootProject.projectDir) {
        include("app/src/**", ".gitignore", "companion/**", "scripts/**", ".github/**")
        exclude("**/build/**")
    }).withPropertyName("repoSecurityScanInputs")
}

// Scorecard-only run of the scenario evals: ./gradlew :core:eval
tasks.register<Test>("eval") {
    group = "verification"
    description = "Runs the scenario eval suite and prints the scorecard."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("eval") }
    testLogging { showStandardStreams = true; events("failed") }
    systemProperty("kavach.reportDir", layout.buildDirectory.dir("reports/kavach-eval").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

// Same scenarios, but the language layer is the REAL local model via Ollama.
tasks.register<Test>("llmEval") {
    group = "verification"
    description = "Runs scenarios against a live Ollama model (set KAVACH_OLLAMA_MODEL, default gemma3:4b)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live-llm") }
    testLogging { showStandardStreams = true; events("failed") }
    systemProperty("kavach.reportDir", layout.buildDirectory.dir("reports/kavach-eval").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
