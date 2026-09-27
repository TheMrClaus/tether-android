package com.tether.app.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * T3.4: the debug-only Component Gallery (PLAN §7 Phase 3). Lives in the app's `debug` source
 * set, so neither this class nor its manifest entry exists in a release build (asserted by
 * `src/testRelease/.../GalleryAbsentFromReleaseTest`). Entry point: the "Tether Gallery" launcher
 * icon of a debug install, or `adb shell am start -n com.tether.app/.gallery.GalleryActivity`.
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ComponentGallery() }
    }
}
