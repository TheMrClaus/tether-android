package com.tether.app.ui.settings

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import com.tether.app.client.PairingCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * r2 (security F1): Android 10+ gives a backgrounded app no clipboard. A clear due while Tether is in
 * the background must neither fail silently forever nor clear blindly: it stays pending and runs on
 * the next resume once due, and only a clip that is positively this copy is ever cleared.
 */
@RunWith(RobolectricTestRunner::class)
class PairingClipboardTest {
    /** A clipboard whose reads the test can refuse, as the system does for a backgrounded app. */
    private class FakeClip : ClipAccess {
        var label: CharSequence? = null
        var text: CharSequence? = null
        var background = false
        var clears = 0
        override fun label() = if (background) null else label
        override fun text() = if (background) null else text
        override fun set(clip: ClipData) {
            label = clip.description.label
            text = clip.getItemAt(0).text
        }
        override fun clear() {
            clears++
            label = null
            text = null
        }
    }

    private val fake = FakeClip()
    private val clip = AndroidPairingClipboard(fake, Handler(Looper.getMainLooper()))
    private val code = PairingCode("SNTL7Q9Z")
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun aClearDueInTheBackgroundRunsOnTheNextResume() {
        assertTrue(clip.copy(code))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("unreadable: not cleared blindly", "SNTL7Q9Z", fake.text.toString())
        clip.onResume()
        assertEquals("still in the background", 0, fake.clears)
        fake.background = false
        clip.onResume()
        assertEquals(1, fake.clears)
        assertNull(fake.text)
    }

    @Test fun aResumeBeforeTheDeadlineClearsNothing() {
        clip.copy(code)
        idle(1_000)
        clip.onResume()
        assertEquals(0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text.toString())
    }

    @Test fun anExpiryOrCloseInTheBackgroundIsDueAtOnceOnResume() {
        clip.copy(code)
        fake.background = true
        clip.clearIfHolds(code)
        assertEquals(0, fake.clears)
        fake.background = false
        clip.onResume()
        assertEquals(1, fake.clears)
    }

    @Test fun aClipThatIsNotOursIsNeverCleared() {
        clip.copy(code)
        fake.background = true
        // Copied by the operator meanwhile (another app's clip): ours is gone.
        fake.label = "note"
        fake.text = "the operator's own text"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        fake.background = false
        clip.onResume()
        assertEquals(0, fake.clears)
        // The same label but other text is not this copy either.
        clip.copy(code)
        fake.text = "XXXXXXXX"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(0, fake.clears)
        // Once decided "not ours", nothing is pending any more.
        fake.text = "SNTL7Q9Z"
        clip.onResume()
        assertEquals(0, fake.clears)
    }

    @Test fun anotherCodeIsNotThePendingCopy() {
        clip.copy(code)
        clip.clearIfHolds(PairingCode("OTHER234"))
        assertEquals(0, fake.clears)
    }
}
