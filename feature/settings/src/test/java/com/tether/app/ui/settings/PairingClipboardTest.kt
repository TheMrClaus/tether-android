package com.tether.app.ui.settings

import android.app.Application
import android.content.ClipboardManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.PairingCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * ta-coik.15: "Copy code" as the web's (paired-devices.tsx copyCode, `navigator.clipboard.writeText`):
 * the code goes onto the clipboard, marked sensitive (a platform hint), and stays there. Nothing
 * clears it: not the old 30 s timer, not the code's expiry, not a resume, a window focus or a
 * pause/stop of the app.
 */
@RunWith(RobolectricTestRunner::class)
class PairingClipboardTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val system = app.getSystemService(ClipboardManager::class.java)
    private val clip = AndroidPairingClipboard(app)
    private val code = PairingCode("SNTL7Q9Z")

    private fun text(): String? = system.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Test fun copyPutsTheCodeOnASensitiveClip() {
        assertTrue(clip.copy(code))
        assertEquals("SNTL7Q9Z", text())
        val description = system.primaryClipDescription!!
        assertEquals(PairingClipboard.CLIP_LABEL, description.label.toString())
        assertTrue("marked sensitive", description.extras!!.getBoolean(PairingClipboard.EXTRA_IS_SENSITIVE))
    }

    @Test fun theCopyStaysPastTheOldDelayAndTheCodesLife() {
        assertTrue(clip.copy(code))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OLD_CLEAR_AFTER_MS + 1_000))
        assertEquals("still there after the old 30 s clear", "SNTL7Q9Z", text())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(2))
        assertEquals("still there past the code's life", "SNTL7Q9Z", text())
    }

    @Test fun theCopyStaysOverAStopAResumeAndAFocus() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        assertTrue(clip.copy(code))
        activity.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OLD_CLEAR_AFTER_MS + 1_000))
        activity.restart().resume().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("SNTL7Q9Z", text())
        activity.pause().stop().destroy()
    }

    @Test fun noClipboardCopiesNothing() {
        assertFalse(AndroidPairingClipboard(null as ClipboardManager?).copy(code))
        assertFalse(PairingClipboard.None.copy(code))
    }

    private companion object {
        /** The clear the app used to run (removed in ta-coik.15). */
        const val OLD_CLEAR_AFTER_MS = 30_000L
    }
}
