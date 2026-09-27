// Local persistence (DataStore): credentials/server settings and UI prefs.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tether.app.core.data"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
