import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Global quick-capture hotkey (Win32 RegisterHotKey).
    implementation("net.java.dev.jna:jna-platform:5.14.0")
}

compose.desktop {
    application {
        mainClass = "uk.tomlowndes.capture.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Vault Capture"
            // MSI versions need three numbers.
            packageVersion = providers.gradleProperty("captureVersion").get() + ".0"
            description = "Quick capture into an Obsidian vault"
            vendor = "Tom Lowndes"
            // java.prefs for settings, java.net.http not needed; jdk.unsupported for JNA.
            modules("java.prefs", "jdk.unsupported")
            windows {
                menuGroup = "Vault Capture"
                shortcut = true
                perUserInstall = true
                // Keep this the same forever so new versions upgrade the installed one.
                upgradeUuid = "6f1b3c2e-6a4d-4f0e-9a51-2c7d8e4b9f10"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}
