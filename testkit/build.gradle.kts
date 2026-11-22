plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // The contract suites are JUnit 4 classes that other modules subclass, so these are API.
    api(libs.junit)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
}
