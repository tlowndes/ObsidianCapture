import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uk.tomlowndes.capture"
    // Compose 1.12.1 (BOM 2026.09.00) declares minCompileSdk 37. targetSdk stays 35,
    // which is what opts the app into Android 15 edge-to-edge and foreground-service rules.
    compileSdk = 37
    defaultConfig {
        applicationId = "uk.tomlowndes.capture"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = providers.gradleProperty("captureVersion").get()
    }
    // The same debug key on every machine and in CI, so any build installs over any other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(project(":core"))
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Reads scanned notebook pages on the phone (bundled Latin model, no network).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Gemini Nano on the phone (Pixel 9/10 and others with AICore), for the on-phone Jarvis brain.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Material3 no longer pulls icons in. These are the same outlined icons the screens already use.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The android.jar stub of org.json throws on every call. Put the real library in front of it.
    testImplementation("org.json:json:20240303")
}

tasks.withType<Test>().configureEach {
    val json = classpath.files.filter { it.name.startsWith("json-") }
    if (json.isNotEmpty()) classpath = files(json, classpath.filter { !it.name.startsWith("json-") })
}
