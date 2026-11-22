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
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    api(project(":core"))
    // The official SDK is confined to this module; nothing else in the app sees its types.
    implementation(libs.anthropic.java)
    testImplementation(project(":testkit"))
    testImplementation(libs.okhttp.mockwebserver)
}
