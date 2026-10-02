package com.tether.app.ui.settings

import android.app.Application
import android.content.ClipData
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.window.Dialog
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.PairingCode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.util.ReflectionHelpers

/**
 * r2 (security F1): Android 10+ gives an app without input focus no clipboard. A clear due while
 * Tether cannot read it must neither fail silently forever nor clear blindly: it stays pending and
 * is retried, and only a clip that is positively this copy is ever cleared.
 *
 * ta-x5e (R2-1): focus arrives after the resume, so the retry also runs on window focus (the
 * activity's window and the Settings dialog's own), and retries end with the code's life. The
 * focus tests make the fake readable exactly while the window has focus, as the platform does, and
 * drive focus through the real ViewRootImpl dispatch.
 */
@RunWith(RobolectricTestRunner::class)
class PairingClipboardTest {
    /** A clipboard whose reads the test can refuse, as the system does for an app without focus. */
    private class FakeClip : ClipAccess {
        var label: CharSequence? = null
        var text: CharSequence? = null
        var background = false
        var readable: () -> Boolean = { !background }
        var clears = 0
        var reads = 0
        override fun label(): CharSequence? {
            reads++
            return if (readable()) label else null
        }
        override fun text() = if (readable()) text else null
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

    /** An activity with focus whose window decides whether [fake] can be read; the clipboard hooked into the app as [AndroidPairingClipboard.forApp] does. */
    private fun focusedActivity(): ActivityController<ComponentActivity> {
        clip.hookInto(ApplicationProvider.getApplicationContext<Application>())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val window = controller.get().window
        fake.readable = { window.decorView.hasWindowFocus() }
        controller.windowFocusChanged(true)
        idle(0)
        return controller
    }

    /** Off to another app: paused, no focus, so no clipboard. */
    private fun ActivityController<ComponentActivity>.leave() {
        windowFocusChanged(false)
        pause()
        idle(0)
    }

    /** Back: resumed first, focus only after (on resume the read is still refused). */
    private fun ActivityController<ComponentActivity>.comeBack(): ActivityController<ComponentActivity> {
        resume()
        idle(0)
        return this
    }

    private fun ActivityController<ComponentActivity>.focus() {
        windowFocusChanged(true)
        idle(0)
    }

    @Test fun aClearPendingWhileUnfocusedRunsOnceTheWindowGainsFocus() {
        val activity = focusedActivity()
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("unreadable: not cleared blindly", "SNTL7Q9Z", fake.text.toString())
        val readsBefore = fake.reads
        activity.comeBack()
        assertTrue("the resume tried", fake.reads > readsBefore)
        assertEquals("resumed but not focused yet: still refused, still pending (R2-1)", 0, fake.clears)
        activity.focus()
        assertEquals("cleared once focus arrived", 1, fake.clears)
        assertNull(fake.text)
        // Cleared: nothing is pending, so nothing reads the clipboard again.
        val reads = fake.reads
        activity.leave()
        activity.comeBack().focus()
        assertEquals(reads, fake.reads)
    }

    @Test fun focusOnTheSettingsDialogsOwnWindowRetriesTheClear() {
        // No activity hook here: only the dialog's own window is watched (RetryClipboardClearOnFocus).
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { Dialog(onDismissRequest = {}) { RetryClipboardClearOnFocus(clip) } }
        idle(1_000)
        val dialogDecor = ShadowDialog.getLatestDialog()!!.window!!.decorView
        val dialogRoot = Shadow.extract<ShadowViewRootImpl>(ReflectionHelpers.callInstanceMethod<Any>(dialogDecor, "getViewRootImpl"))
        fake.readable = { dialogDecor.hasWindowFocus() }
        assertTrue(clip.copy(code, LIFE))
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("the dialog has no focus: not cleared blindly", 0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text.toString())
        dialogRoot.callWindowFocusChanged(true)
        idle(0)
        assertEquals("cleared once the dialog's window gained focus", 1, fake.clears)
        assertNull(fake.text)
    }

    @Test fun aNewerClipIsNeverClearedWhenFocusArrives() {
        val activity = focusedActivity()
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        // The operator copied something else meanwhile (in another app).
        fake.label = "note"
        fake.text = "the operator's own text"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("the operator's own text", fake.text.toString())

        // The same text under another label is the operator's clip too, not this copy.
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        fake.label = "note"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text.toString())

        // Our label but other text is not this copy either.
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        fake.text = "XXXXXXXX"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("XXXXXXXX", fake.text.toString())
    }

    @Test fun noRetryOnceTheCodeHasExpired() {
        val activity = focusedActivity()
        assertTrue(clip.copy(code, 60_000))
        activity.leave()
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("the timer tried, unreadable", 0, fake.clears)
        // Still away when the code's life runs out.
        idle(30_000)
        val reads = fake.reads
        activity.comeBack().focus()
        assertEquals("expired: the clipboard is not read for it again", reads, fake.reads)
        assertEquals(0, fake.clears)
        activity.leave()
        activity.comeBack().focus()
        assertEquals(reads, fake.reads)
    }

    @Test fun theTimerDoesNotReadForACodeAlreadyExpired() {
        assertTrue(clip.copy(code, 10_000))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(0, fake.reads)
        fake.background = false
        clip.onWindowFocus()
        assertEquals(0, fake.reads)
        assertEquals(0, fake.clears)
    }

    @Test fun theExpiryItselfStillGetsItsAttempt() {
        assertTrue(clip.copy(code, 10_000))
        idle(10_000)
        // The controller's expiry lands at (or a hair after) the copy's own deadline: it still clears.
        clip.clearIfHolds(code)
        assertEquals(1, fake.clears)
    }

    @Test fun aCodeWithNoLifeLeftIsNotCopied() {
        assertFalse(clip.copy(code, 0))
        assertNull(fake.text)
    }

    @Test fun aClearDueInTheBackgroundRunsOnTheNextResume() {
        assertTrue(clip.copy(code, LIFE))
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
        clip.copy(code, LIFE)
        idle(1_000)
        clip.onResume()
        clip.onWindowFocus()
        assertEquals(0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text.toString())
    }

    @Test fun anExpiryOrCloseInTheBackgroundIsDueAtOnceOnResume() {
        clip.copy(code, LIFE)
        fake.background = true
        clip.clearIfHolds(code)
        assertEquals(0, fake.clears)
        fake.background = false
        clip.onResume()
        assertEquals(1, fake.clears)
    }

    @Test fun aClipThatIsNotOursIsNeverCleared() {
        clip.copy(code, LIFE)
        fake.background = true
        // Copied by the operator meanwhile (another app's clip): ours is gone.
        fake.label = "note"
        fake.text = "the operator's own text"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        fake.background = false
        clip.onResume()
        assertEquals(0, fake.clears)
        // The same label but other text is not this copy either.
        clip.copy(code, LIFE)
        fake.text = "XXXXXXXX"
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(0, fake.clears)
        // Once decided "not ours", nothing is pending any more.
        fake.text = "SNTL7Q9Z"
        clip.onResume()
        assertEquals(0, fake.clears)
    }

    @Test fun anotherCodeIsNotThePendingCopy() {
        clip.copy(code, LIFE)
        clip.clearIfHolds(PairingCode("OTHER234"))
        assertEquals(0, fake.clears)
    }

    private companion object {
        /** A fresh code's life (the server's 5 minutes, less a second). */
        const val LIFE = 299_000L
    }
}
