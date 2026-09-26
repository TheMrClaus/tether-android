// Theme, tokens, fonts (res/font), shared components and display formatting.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tether.app.core.designsystem"
    buildFeatures {
        compose = true
    }
}

dependencies {
    // ThemeChoice (the persisted theme preference) is part of TetherTheme's API.
    api(project(":core:data"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
}
