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
    // HttpUrl only: the credential store binds a credential to the server origin
    // exactly as the network layer canonicalises it.
    implementation(libs.okhttp)
    // T2.3: the ported lib/panel-widths.mjs parse (PanelWidths) for the stored column widths.
    implementation(project(":core:reducer"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
