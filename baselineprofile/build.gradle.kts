import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ta-gmyi: Baseline Profile generator + startup macrobenchmark. A com.android.test module that
// instruments :app's `benchmarkRelease` (R8-minified like release, debug-signed, profileable) /
// `nonMinifiedRelease` builds. Nothing here ships in the app. Needs a device (the lead's emulator):
// see README.md for the exact commands. AGP 9 has built-in Kotlin, so org.jetbrains.kotlin.android
// is not applied (same as every other module).
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.tether.baselineprofile"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 34
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

baselineProfile {
    // The device the generator / benchmark run on is whatever `adb` has connected (the emulator).
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
