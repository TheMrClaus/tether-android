package com.tether.app.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * activity's window and the Settings dialog's own). The focus tests make the fake readable exactly
 * while the window has focus, as the platform does, and drive focus through the real ViewRootImpl
 * dispatch. ta-x5e r2: the description decides before the clip is fetched; retries end an hour past
 * the code's (capped) life; deep sleep is [sleptMs] (realtime moves, uptime and the looper do not).
 */
@RunWith(RobolectricTestRunner::class)
class PairingClipboardTest {
    /** A clipboard whose reads the test can refuse, as the system does for an app without focus. */
    private class FakeClip : ClipAccess {
        var current: ClipData? = null
        var background = false
        var readable: () -> Boolean = { !background }
        var clears = 0
        var descriptionReads = 0
        var clipReads = 0
        var clearThrows = 0

        /** When set, what the clip read returns (it changed between the two reads). */
        var clipInstead: ClipData? = null
        val reads get() = descriptionReads + clipReads

        override fun description(): ClipDescription? {
            descriptionReads++
            return if (readable()) current?.description else null
        }
        override fun clip(): ClipData? {
            clipReads++
            return if (readable()) clipInstead ?: current else null
        }
        override fun set(clip: ClipData) {
            current = clip
        }
        override fun clear() {
            if (clearThrows > 0) {
                clearThrows--
                throw SecurityException("refused")
            }
            clears++
            current = null
        }

        fun text(): String? = current?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

        /** Something the operator copied (in another app). */
        fun userCopies(label: String, text: String) {
            current = ClipData.newPlainText(label, text)
        }
    }

    private val fake = FakeClip()
    private var sleptMs = 0L
    private val clip = AndroidPairingClipboard(fake, Handler(Looper.getMainLooper()), realtime = { SystemClock.elapsedRealtime() + sleptMs })
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

    // ---- focus (R2-1) ---------------------------------------------------------------------------

    @Test fun aClearPendingWhileUnfocusedRunsOnceTheWindowGainsFocus() {
        val activity = focusedActivity()
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("unreadable: not cleared blindly", "SNTL7Q9Z", fake.text())
        val readsBefore = fake.reads
        activity.comeBack()
        assertTrue("the resume tried", fake.reads > readsBefore)
        assertEquals("resumed but not focused yet: still refused, still pending (R2-1)", 0, fake.clears)
        activity.focus()
        assertEquals("cleared once focus arrived", 1, fake.clears)
        assertEquals("our clip fetched once, to check its text", 1, fake.clipReads)
        assertNull(fake.text())
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
        assertEquals("SNTL7Q9Z", fake.text())
        dialogRoot.callWindowFocusChanged(true)
        idle(0)
        assertEquals("cleared once the dialog's window gained focus", 1, fake.clears)
        assertNull(fake.text())
    }

    /** r2 (verifier P4-1): the listener comes off the dialog's window as it goes, not later off a floating observer. */
    @Test fun theDialogsFocusListenerLeavesWithItsWindow() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var open by mutableStateOf(true)
        activity.setContent { if (open) Dialog(onDismissRequest = {}) { RetryClipboardClearOnFocus(clip) } }
        idle(1_000)
        val observer = ShadowDialog.getLatestDialog()!!.window!!.decorView.viewTreeObserver
        assertTrue("control: watched while open", holdsOurs(observer))
        open = false
        idle(1_000)
        assertFalse("the closed dialog's window keeps no listener of ours", holdsOurs(observer))
    }

    /** Whether [observer] holds the clipboard's own focus listener (Compose registers one of its own as well). */
    private fun holdsOurs(observer: ViewTreeObserver): Boolean {
        val ours = ReflectionHelpers.getField<Any>(clip, "focusListener")
        return ReflectionHelpers.getField<Collection<*>?>(observer, "mOnWindowFocusListeners")?.any { it === ours } == true
    }

    // ---- only ours, read label-first (P3-1) -----------------------------------------------------

    @Test fun aNewerClipIsNeverClearedWhenFocusArrives() {
        val activity = focusedActivity()
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        fake.userCopies("note", "the operator's own text")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("the operator's own text", fake.text())

        // The same text under another label is the operator's clip too, not this copy.
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        fake.userCopies("note", "SNTL7Q9Z")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text())

        // Our label but other text is not this copy either.
        assertTrue(clip.copy(code, LIFE))
        activity.leave()
        fake.userCopies(PairingClipboard.CLIP_LABEL, "XXXXXXXX")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        activity.comeBack().focus()
        assertEquals(0, fake.clears)
        assertEquals("XXXXXXXX", fake.text())
    }

    @Test fun aForeignLabelNeverFetchesTheClip() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        fake.userCopies("note", "the operator's own text")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        fake.background = false
        clip.onWindowFocus()
        assertEquals("no getPrimaryClip on someone else's clip (no paste notice, no URI grant)", 0, fake.clipReads)
        assertEquals(0, fake.clears)
        assertEquals("the operator's own text", fake.text())
        // And that ended it: nothing reads the clipboard for this copy again.
        val reads = fake.reads
        clip.onWindowFocus()
        clip.onResume()
        assertEquals(reads, fake.reads)
    }

    @Test fun aNonTextClipEndsTheRetriesUnfetched() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        // Our label, but an image: not this copy, and its content is not fetched.
        fake.current = ClipData(ClipDescription(PairingClipboard.CLIP_LABEL, arrayOf("image/png")), ClipData.Item(Uri.parse("content://media/external/images/1")))
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        fake.background = false
        clip.onWindowFocus()
        assertEquals(0, fake.clipReads)
        assertEquals(0, fake.clears)
        val reads = fake.reads
        clip.onWindowFocus()
        assertEquals("ended, not taken for 'no focus'", reads, fake.reads)
    }

    @Test fun anEmptyClipUnderOurLabelEndsTheRetries() {
        assertTrue(clip.copy(code, LIFE))
        fake.current = ClipData(ClipDescription(PairingClipboard.CLIP_LABEL, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item(null as CharSequence?))
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(1, fake.clipReads)
        assertEquals(0, fake.clears)
        val reads = fake.reads
        clip.onWindowFocus()
        clip.onResume()
        assertEquals("ended, not taken for 'no focus'", reads, fake.reads)
    }

    @Test fun theFetchedClipsOwnLabelDecides() {
        assertTrue(clip.copy(code, LIFE))
        // The description still read ours; by the clip read the operator had copied the same text under another label.
        fake.clipInstead = ClipData.newPlainText("note", "SNTL7Q9Z")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(1, fake.clipReads)
        assertEquals(0, fake.clears)
    }

    // ---- bounded retries (P3-2, P4-1) -----------------------------------------------------------

    @Test fun aDeadCodeIsClearedWhenTheAppComesBackFromDeepSleep() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        // Ten minutes asleep: realtime moves, uptime does not (the 30 s timer has not even fired).
        sleptMs += 10 * 60_000L
        fake.background = false
        clip.onWindowFocus()
        assertEquals("past the code's life, within the hour: still taken off", 1, fake.clears)
        assertNull(fake.text())
    }

    @Test fun aDeadCodeIsClearedUpToTheHorizon() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        sleptMs += LIFE + PairingClipboard.RETRY_PAST_LIFE_MS - 60_000L
        fake.background = false
        clip.onResume()
        assertEquals(1, fake.clears)
    }

    @Test fun pastTheHorizonNothingIsRead() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        sleptMs += LIFE + PairingClipboard.RETRY_PAST_LIFE_MS
        fake.background = false
        val reads = fake.reads
        clip.onResume()
        clip.onWindowFocus()
        assertEquals("retired: the clipboard is not read for it again", reads, fake.reads)
        assertEquals(0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text())
    }

    @Test fun aLifeLongerThanTheCapIsCut() {
        // A device clock far behind the server's would hand a day of life; ten minutes are credited.
        assertTrue(clip.copy(code, 24 * 3_600_000L))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        sleptMs += PairingClipboard.MAX_LIFE_MS + PairingClipboard.RETRY_PAST_LIFE_MS
        fake.background = false
        val reads = fake.reads
        clip.onWindowFocus()
        assertEquals(reads, fake.reads)
        assertEquals(0, fake.clears)
    }

    @Test fun theExpiryItselfStillGetsItsAttempt() {
        assertTrue(clip.copy(code, 10_000))
        idle(10_000)
        clip.clearIfHolds(code)
        assertEquals(1, fake.clears)
    }

    @Test fun aCodeWithNoLifeLeftIsNotCopied() {
        assertFalse(clip.copy(code, 0))
        assertNull(fake.current)
    }

    // ---- pending until the clear went through (verifier P4-2) ------------------------------------

    @Test fun aClearThatThrowsStaysPending() {
        assertTrue(clip.copy(code, LIFE))
        fake.clearThrows = 1
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("the clear threw: no crash, nothing cleared", 0, fake.clears)
        assertEquals("SNTL7Q9Z", fake.text())
        clip.onWindowFocus()
        assertEquals("still pending: cleared on the next focus", 1, fake.clears)
        assertNull(fake.text())
    }

    // ---- the r1 rules -----------------------------------------------------------------------------

    @Test fun aClearDueInTheBackgroundRunsOnTheNextResume() {
        assertTrue(clip.copy(code, LIFE))
        fake.background = true
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals("unreadable: not cleared blindly", "SNTL7Q9Z", fake.text())
        clip.onResume()
        assertEquals("still in the background", 0, fake.clears)
        fake.background = false
        clip.onResume()
        assertEquals(1, fake.clears)
        assertNull(fake.text())
    }

    @Test fun aResumeBeforeTheDeadlineClearsNothing() {
        clip.copy(code, LIFE)
        idle(1_000)
        clip.onResume()
        clip.onWindowFocus()
        assertEquals(0, fake.clears)
        assertEquals(0, fake.reads)
        assertEquals("SNTL7Q9Z", fake.text())
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
        fake.userCopies("note", "the operator's own text")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        fake.background = false
        clip.onResume()
        assertEquals(0, fake.clears)
        // The same label but other text is not this copy either.
        clip.copy(code, LIFE)
        fake.userCopies(PairingClipboard.CLIP_LABEL, "XXXXXXXX")
        idle(PairingClipboard.CLEAR_AFTER_MS + 1_000)
        assertEquals(0, fake.clears)
        // Once decided "not ours", nothing is pending any more.
        fake.userCopies(PairingClipboard.CLIP_LABEL, "SNTL7Q9Z")
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
