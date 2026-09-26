// Session drawer (sidebar): session list, workspaces, drawer settings.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tether.app.feature.sidebar"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:data"))
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
}
