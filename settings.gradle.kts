pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kavach"

// :core is pure Kotlin/JVM (risk engine, orchestrator, LLM routing, report). Fully testable on a laptop.
include(":core")
// :app (Android) is added once the Android SDK is configured; see docs/ANDROID_SETUP.md
if (file("app/build.gradle.kts").exists()) include(":app")
