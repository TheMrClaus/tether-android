// Local persistence (DataStore): credentials/server settings and UI prefs.
// T13.1: the Room journal mirror (com.tether.app.mirror, SYNC_DESIGN §2.2 / §8).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.tether.app.core.data"
    // T13.1: the Room mirror tests run on Robolectric (JVM SQLite).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // HttpUrl only: the credential store binds a credential to the server origin
    // exactly as the network layer canonicalises it.
    implementation(libs.okhttp)
    // T2.3: the ported lib/panel-widths.mjs parse (PanelWidths) for the stored column widths.
    implementation(project(":core:reducer"))

    // api: MirrorDatabase (a RoomDatabase) is part of the mirror API.
    api(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // ta-js0: CredentialCipherProviderTest pins SunJCE and Conscrypt explicitly.
    testImplementation(libs.conscrypt.openjdk.uber)
}
