// Login / pairing screen.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tether.app.feature.auth"
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric (LoginScreenBehaviourTest) needs the merged resources: fonts, the test activity.
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:designsystem"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.lucide.icons)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // ta-s4r: the real screen over the real client against a MockWebServer "Tether".
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
