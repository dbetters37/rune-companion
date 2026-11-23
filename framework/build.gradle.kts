plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Compile against the JDK 17 API surface even on a newer JDK. This module ships inside
        // an Android app, where members that newer JDKs add to collections (removeFirst,
        // getFirst, reversed ...) do not exist before Android 15.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":core"))
    // scripts.run_js: Rhino in interpreted mode with a deny-all ClassShutter (operator/JsSandbox.kt).
    implementation(libs.rhino)
    testImplementation(project(":testkit"))
}

tasks.withType<Test>().configureEach {
    // Android's file system always uses UTF-8 names; the workspace tests create names with
    // non-ASCII characters, which a JVM started in the POSIX locale cannot represent.
    environment("LC_ALL", "C.UTF-8")
}
