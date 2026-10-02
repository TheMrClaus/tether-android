package com.tether.app.ui.settings

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.tether.app.client.PairingCode
import java.security.MessageDigest

/**
 * T10.4: where "Copy code" puts the fresh pairing code (paired-devices.tsx offers Copy code, so
 * the app does too). The clip is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`: the
 * system's paste preview and clipboard overlay do not show it), and it is taken off the clipboard
 * again after [CLEAR_AFTER_MS], when the code expires, and when Settings closes, but only while the
 * clipboard still holds THIS copy (something copied since is left alone). Nothing of the code is
 * kept for that check but its SHA-256; nothing is ever logged.
 */
interface PairingClipboard {
    /**
     * Put [code] on the clipboard (sensitive) for the [lifeMs] it has left; false when there is no
     * clipboard, or no life left (a dead code is not put where no clear would follow it).
     */
    fun copy(code: PairingCode, lifeMs: Long): Boolean

    /** Take [code] off the clipboard if it is still the clip there. */
    fun clearIfHolds(code: PairingCode)

    /** No clipboard (previews, goldens): nothing is ever copied. */
    object None : PairingClipboard {
        override fun copy(code: PairingCode, lifeMs: Long) = false
        override fun clearIfHolds(code: PairingCode) = Unit
    }

    companion object {
        const val CLEAR_AFTER_MS = 30_000L
        const val CLIP_LABEL = "Tether pairing code"

        /** The platform's own key (API 33; minSdk is above it). */
        const val EXTRA_IS_SENSITIVE = android.content.ClipDescription.EXTRA_IS_SENSITIVE
    }
}

/**
 * What [AndroidPairingClipboard] asks of the system clipboard. A read the system refuses (Android 10+
 * gives an app without input focus no clipboard: the description and the clip read as null) comes
 * back null, never as "not ours".
 */
interface ClipAccess {
    fun label(): CharSequence?
    fun text(): CharSequence?
    fun set(clip: ClipData)
    fun clear()

    class System(private val manager: ClipboardManager) : ClipAccess {
        override fun label(): CharSequence? = manager.primaryClipDescription?.label
        override fun text(): CharSequence? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        override fun set(clip: ClipData) = manager.setPrimaryClip(clip)
        override fun clear() = manager.clearPrimaryClip()
    }
}

/**
 * The app's [PairingClipboard] over the system clipboard, its timer on the main looper (it outlives
 * the dialog). Main thread only.
 *
 * r2 (security F1): Android 10+ lets only the app in focus read the clipboard, so a clear due while
 * Tether cannot read it (the 30 s timer, an expiry, a close in the background) cannot tell whether
 * the clip is still ours. It is then NOT done blindly: the copy's stamp stays pending and the clear
 * is retried. A clip is cleared only when it can be read and is positively this copy (its label and
 * its text's SHA-256); anything else is never touched.
 *
 * ta-x5e (T10.4 r2 review, R2-1): the read is allowed once one of the app's windows has input
 * focus, and that comes AFTER the activity resumes, so a retry on resume alone can still read null.
 * The retry therefore also runs when a watched window gains focus ([watchFocus]: every activity's
 * window, wired by [forApp]; the Settings dialog's own window, [RetryClipboardClearOnFocus]). Retries
 * stop once the copy is cleared or found not ours, and once the code's life is over (it is then
 * worthless; nothing reads the clipboard for it again).
 */
class AndroidPairingClipboard(
    private val access: ClipAccess?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clearAfterMs: Long = PairingClipboard.CLEAR_AFTER_MS,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
    /** The code's life is measured on this: it counts deep sleep (unlike [uptime]) and a wall-clock jump does not move it. */
    private val realtime: () -> Long = SystemClock::elapsedRealtime,
) : PairingClipboard {
    constructor(context: Context) : this(context.applicationContext.getSystemService(ClipboardManager::class.java)?.let(ClipAccess::System))

    private val token = Any()

    /** The copy still on the clipboard as far as known: its stamp, when it is due off (uptime) and when the code dies (realtime). */
    private class Pending(val stamp: ByteArray, var dueAt: Long, val deadAt: Long)

    private var pending: Pending? = null

    /** One listener for every watched window: a gain of input focus is when Android 10+ allows the read. */
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) onWindowFocus() }

    override fun copy(code: PairingCode, lifeMs: Long): Boolean {
        val a = access ?: return false
        if (lifeMs <= 0) return false
        val clip = ClipData.newPlainText(PairingClipboard.CLIP_LABEL, code.reveal())
        clip.description.extras = PersistableBundle().apply { putBoolean(PairingClipboard.EXTRA_IS_SENSITIVE, true) }
        return try {
            a.set(clip)
            pending = Pending(digest(code.reveal()), uptime() + clearAfterMs, realtime() + lifeMs)
            handler.removeCallbacksAndMessages(token)
            handler.postAtTime({ retry() }, token, SystemClock.uptimeMillis() + clearAfterMs)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * The code expired or Settings closed: if [code] is the copy still pending, it is due off now.
     * This is the event itself, so it gets its attempt even at the instant of expiry; only the
     * retries after it are bounded by the code's life.
     */
    override fun clearIfHolds(code: PairingCode) {
        val p = pending ?: return
        if (!MessageDigest.isEqual(p.stamp, digest(code.reveal()))) return
        p.dueAt = minOf(p.dueAt, uptime())
        attempt()
    }

    /** An activity of the app resumed: a clear that came due meanwhile is retried (it may still read null, focus comes later). */
    fun onResume() = retry()

    /** A watched window gained input focus: the clipboard can be read now, so a clear that came due is retried. */
    fun onWindowFocus() = retry()

    /** Retry the clear whenever [view]'s window gains input focus. Idempotent (one registration per window). */
    fun watchFocus(view: View) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        observer.removeOnWindowFocusChangeListener(focusListener)
        observer.addOnWindowFocusChangeListener(focusListener)
    }

    fun unwatchFocus(view: View) {
        view.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
    }

    /** The timer, a resume or a focus: past the code's life the copy is dropped unread; otherwise a due clear is attempted. */
    private fun retry() {
        val p = pending ?: return
        if (realtime() >= p.deadAt) return drop()
        if (uptime() >= p.dueAt) attempt()
    }

    /** Clear the pending copy if it is due and the clipboard can be read and still holds it; keep it pending while it cannot be read. */
    private fun attempt() {
        val a = access ?: return
        val p = pending ?: return
        if (uptime() < p.dueAt) return
        try {
            val label = a.label()
            val text = a.text()
            if (label == null || text == null) return // unreadable (no focus): retried on resume and on focus
            drop()
            if (label.toString() != PairingClipboard.CLIP_LABEL) return // something else was copied since
            if (!MessageDigest.isEqual(digest(text.toString()), p.stamp)) return
            a.clear()
        } catch (_: RuntimeException) {
            // Best effort: still pending, retried on resume and on focus; the clip is marked sensitive either way.
        }
    }

    private fun drop() {
        pending = null
        handler.removeCallbacksAndMessages(token)
    }

    private fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    /** Retry on every activity resume and on every activity window's focus (security F1, ta-x5e). */
    internal fun hookInto(application: Application) = application.registerActivityLifecycleCallbacks(LifecycleHook(this))

    companion object {
        @Volatile private var app: AndroidPairingClipboard? = null

        /** The one clipboard of the process, its clears retried on every activity resume and window focus. */
        fun forApp(context: Context): AndroidPairingClipboard = app ?: synchronized(this) {
            app ?: AndroidPairingClipboard(context).also { clip ->
                (context.applicationContext as? Application)?.let(clip::hookInto)
                // The activity Settings opened in resumed before the hook existed: watch its window now.
                context.findActivity()?.let { clip.watchFocus(it.window.decorView) }
                app = clip
            }
        }
    }

    private class LifecycleHook(private val clip: AndroidPairingClipboard) : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            clip.watchFocus(activity.window.decorView)
            clip.onResume()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/**
 * ta-x5e: Settings is a dialog, a window of its own; coming back to the app with it open, input
 * focus goes to it, not to the activity under it. While composed, a gain of focus by THIS window
 * retries a pending clear (see [AndroidPairingClipboard.watchFocus]).
 */
@Composable
fun RetryClipboardClearOnFocus(clipboard: AndroidPairingClipboard) {
    val view = LocalView.current
    DisposableEffect(view, clipboard) {
        clipboard.watchFocus(view)
        onDispose { clipboard.unwatchFocus(view) }
    }
}
