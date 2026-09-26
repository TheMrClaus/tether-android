// Main shell: drawer + chat layout, toasts. Composes :feature:sidebar and :feature:chat.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tether.app.feature.shell"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":feature:chat"))
    implementation(project(":feature:sidebar"))
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
