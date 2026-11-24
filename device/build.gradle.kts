plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aicontrolcenter.device"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Lets QueriesManifestTest read the source manifest whatever the working directory is.
            all { it.systemProperty("aicc.deviceManifest", "$projectDir/src/main/AndroidManifest.xml") }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    // Custom Tabs for AppActions.openInBrowser (the owner's own browser session, not a WebView).
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // The owner's screen-operator Stop survives restarts.
    implementation(libs.androidx.datastore.preferences)

    testImplementation(project(":testkit"))
    // The registry is exercised against the real scanner to prove the two fit together.
    testImplementation(project(":framework"))
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)

    // On a device: the shell runner against real processes (ShellLeftoversDeviceTest). :testkit is absent, as in :data:
    // its method names with spaces cannot be dexed for devices below API 30.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
}
