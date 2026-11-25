plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.aicontrolcenter.app"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.aicontrolcenter.hq"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.5.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Every build is signed with the same key so that a newer build installs over an older one
    // without uninstalling (which would wipe locally stored agents, memories and keys).
    // By default that is the development key committed in keystore/, which is public: anyone
    // can sign with it. To sign with a private key instead, point AICC_KEYSTORE_FILE at a
    // keystore and set AICC_KEYSTORE_PASSWORD, AICC_KEY_ALIAS and AICC_KEY_PASSWORD (CI reads
    // them from repository secrets; see README).
    val privateKeystore: String? = System.getenv("AICC_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        create("shared") {
            if (privateKeystore != null) {
                storeFile = file(privateKeystore)
                storePassword = System.getenv("AICC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AICC_KEY_ALIAS")
                keyPassword = System.getenv("AICC_KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("keystore/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            // Not debuggable, so adb cannot read the app's data. Code shrinking stays off until
            // keep rules have been proven on a device; the rules are ready in proguard-rules.pro.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
        }
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":framework"))
    implementation(project(":memory"))
    implementation(project(":data"))
    implementation(project(":device"))
    implementation(project(":providers"))
    implementation(project(":providers-anthropic"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(project(":testkit"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
