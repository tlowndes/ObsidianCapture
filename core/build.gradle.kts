plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Vault logic shared by the Android app and the desktop app: no Android or UI code in here.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jsoup:jsoup:1.17.2")
    // Android has org.json built in; the desktop app adds the library itself.
    compileOnly("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
