import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// sherpa-onnx is Apache-2.0 and is not on Maven. v2.0.1 is 47 MB, under the 50 MB gitignore cutoff.
// Keep this URL in sync with scripts/fetch-sherpa-onnx.sh.
val sherpaAar = file("libs/sherpa-onnx-2.0.1.aar")
val sherpaAarUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
if (!sherpaAar.isFile || sherpaAar.length() < 40_000_000L) {
    logger.lifecycle("Fetching sherpa-onnx AAR (not on Maven)...")
    sherpaAar.parentFile.mkdirs()
    val partial = sherpaAar.parentFile.resolve(sherpaAar.name + ".partial")
    URI(sherpaAarUrl).toURL().openStream().use { input ->
        partial.outputStream().use { output -> input.copyTo(output) }
    }
    if (partial.length() < 40_000_000L) {
        val got = partial.length()
        partial.delete()
        error("sherpa-onnx AAR download is truncated ($got bytes). Run scripts/fetch-sherpa-onnx.sh")
    }
    if (sherpaAar.exists()) sherpaAar.delete()
    if (!partial.renameTo(sherpaAar)) {
        partial.copyTo(sherpaAar, overwrite = true)
        partial.delete()
    }
}

android {
    namespace = "com.kavach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kavach.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // OnePlus 7 Pro is arm64-v8a. x86_64 keeps the emulator installable. The AAR also has
            // armeabi-v7a and x86; leaving them out keeps the APK smaller.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // sideloaded hackathon APK
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Deliberate: a sideloaded guardian app needs these (documented in docs/DECISIONS.md and HANDOVER.md).
        disable += setOf("AccessibilityPolicy", "ProtectedPermissions", "QueryAllPackagesPermission", "UnusedAttribute")
    }

    packaging {
        jniLibs {
            // Vosk may ship libc++_shared.so. sherpa-onnx 2.0.1 links the C++ runtime in, but keep
            // the first copy if a later dependency adds another.
            pickFirsts += "**/libc++_shared.so"
        }
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // On-device ASR. vosk-android 0.3.75 already depends on net.java.dev.jna:jna:5.18.1 as an AAR.
    implementation(libs.vosk.android)
    // Optional Whisper engine. The AAR is fetched above if app/libs does not already have it.
    implementation(files(sherpaAar))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
