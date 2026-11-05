package com.tether.app

import java.io.File
import java.util.Properties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-delivery A2 (R4): photo uploads are WorkManager work and nothing else. On the MERGED manifest (library manifests
 * included): no foreground-service permission and no foreground service, so nothing runs always-on.
 */
class UploadManifestTest {
    private fun mergedManifestText(): String {
        val props = Properties()
        val stream = javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")
        assertNotNull("AGP test_config.properties missing (isIncludeAndroidResources)", stream)
        stream!!.use { props.load(it) }
        return File(props.getProperty("android_merged_manifest")).readText()
    }

    @Test
    fun noForegroundServicePermissionIsDeclared() {
        assertFalse(mergedManifestText().contains("FOREGROUND_SERVICE"))
    }

    @Test
    fun noForegroundServiceComponentIsDeclared() {
        val text = mergedManifestText()
        assertFalse(text.contains("SystemForegroundService"))
        assertTrue("WorkManager itself is still merged in", text.contains("androidx.work"))
    }
}
