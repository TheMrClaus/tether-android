package com.tether.app.ui.settings

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
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
 * system's paste preview and clipboard overlay do not show it), and the app tries to take it off
 * the clipboard again after [CLEAR_AFTER_MS], when the code expires, and when Settings closes, but
 * only while the clipboard still holds THIS copy (something copied since is left alone, and its
 * content is never read). When the clipboard cannot be read at that moment (Android 10+, no focus),
 * the clear is retried on resume and on window focus for a bounded time (see
 * [AndroidPairingClipboard]). Nothing of the code is kept for the check but its SHA-256; nothing is
 * ever logged.
 */
interface PairingClipboard {
    /**
     * Put [code] on the clipboard (sensitive) for the [lifeMs] it has left; false when there is no
     * clipboard, or no life left (a dead code is not copied).
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

        /**
         * ta-x5e r2 (P4-1): the most life a copy is credited with, whatever the caller says. The life
         * is the server's expiry less the device's wall clock, so a device clock running far behind
         * the server would otherwise stretch it. The server's codes live 5 minutes.
         */
        const val MAX_LIFE_MS = 10 * 60_000L

        /**
         * ta-x5e r2 (P3-2): how long past the code's (capped) life a clear is still retried. A code
         * dead on the clipboard is worthless but still a secret-shaped clip: if it is still there when
         * the app next gets focus, within this hour, it is taken off. Past it, nothing reads the
         * clipboard for that copy again.
         */
        const val RETRY_PAST_LIFE_MS = 60 * 60_000L
    }
}

/**
 * What [AndroidPairingClipboard] asks of the system clipboard: the description first (no paste
 * notice, no URI grant, no content), the whole clip only once the description says it is ours. A
 * read the system refuses (Android 10+ gives an app without input focus no clipboard) comes back
 * null, never as "not ours".
 */
interface ClipAccess {
    /** `primaryClipDescription`: null when refused (no focus) or when nothing is on the clipboard. */
    fun description(): ClipDescription?

    /** `primaryClip`: the content. Asked for only when [description] shows our label on plain text. */
    fun clip(): ClipData?

    fun set(clip: ClipData)
    fun clear()

    class System(private val manager: ClipboardManager) : ClipAccess {
        override fun description(): ClipDescription? = manager.primaryClipDescription
        override fun clip(): ClipData? = manager.primaryClip
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
 * is retried.
 *
 * ta-x5e (R2-1): the read is allowed once one of the app's windows has input focus, and that comes
 * AFTER the activity resumes, so the retry runs on resume and also when a watched window gains focus
 * ([watchFocus]: every activity's window, wired by [forApp]; the Settings dialog's own window,
 * [RetryClipboardClearOnFocus]).
 *
 * ta-x5e r2: each attempt reads the description first. A foreign label, or a clip that is not plain
 * text, ends the retries at once and the clip itself is never fetched (no Android 12+ paste notice, no
 * URI grant, nothing of the user's content read). Only on our label is the clip fetched, once, and
 * its own label and text decide: our label on plain text whose SHA-256 is this copy's is cleared;
 * anything else (replaced meanwhile, empty, other text) ends the retries untouched. A refused read
 * keeps the copy pending, and so does a clear that throws. All of it is measured on
 * `elapsedRealtime` (deep sleep counts, a wall-clock jump does not move it). Retries end once the copy
 * is cleared or found not ours, or [PairingClipboard.RETRY_PAST_LIFE_MS] after the code's life
 * (capped at [PairingClipboard.MAX_LIFE_MS]) ran out: a dead code still on the clipboard when the app
 * comes back within that hour is taken off; past it, nothing reads the clipboard for it.
 */
class AndroidPairingClipboard(
    private val access: ClipAccess?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clearAfterMs: Long = PairingClipboard.CLEAR_AFTER_MS,
    /** Every deadline is on this clock: it counts deep sleep, and a wall-clock jump does not move it. */
    private val realtime: () -> Long = SystemClock::elapsedRealtime,
) : PairingClipboard {
    constructor(context: Context) : this(context.applicationContext.getSystemService(ClipboardManager::class.java)?.let(ClipAccess::System))

    private val token = Any()

    /** The copy still on the clipboard as far as known: its stamp, when it is due off, and when retries end. */
    private class Pending(val stamp: ByteArray, var dueAt: Long, val retireAt: Long)

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
            val now = realtime()
            pending = Pending(digest(code.reveal()), now + clearAfterMs, now + minOf(lifeMs, PairingClipboard.MAX_LIFE_MS) + PairingClipboard.RETRY_PAST_LIFE_MS)
            handler.removeCallbacksAndMessages(token)
            // The timer runs on uptime (the looper's clock); the due check it runs is on realtime.
            handler.postAtTime({ retry() }, token, SystemClock.uptimeMillis() + clearAfterMs)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The code expired or Settings closed: if [code] is the copy still pending, it is due off now. */
    override fun clearIfHolds(code: PairingCode) {
        val p = pending ?: return
        if (!MessageDigest.isEqual(p.stamp, digest(code.reveal()))) return
        p.dueAt = minOf(p.dueAt, realtime())
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

    /** The timer, a resume or a focus: past the retry horizon the copy is dropped unread; otherwise a due clear is attempted. */
    private fun retry() {
        val p = pending ?: return
        if (realtime() >= p.retireAt) return drop()
        if (realtime() >= p.dueAt) attempt()
    }

    /**
     * Clear the pending copy if it is due and the clipboard can be read and still holds it. The
     * description decides first; the clip is fetched only on our label, and its own label and text
     * decide the clear.
     */
    private fun attempt() {
        val a = access ?: return
        val p = pending ?: return
        if (realtime() < p.dueAt) return
        try {
            val description = a.description() ?: return // refused (no focus) or nothing there: retried on resume and on focus
            if (!isOurs(description)) return drop() // something else was copied since: its content is never fetched
            val clip = a.clip() ?: return // refused between the two reads: retried
            if (!isOurs(clip.description)) return drop() // replaced between the two reads
            val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: return drop() // no text: not this copy
            if (!MessageDigest.isEqual(digest(text.toString()), p.stamp)) return drop()
            a.clear()
            drop() // only once the clear went through: a clear that throws stays pending
        } catch (_: RuntimeException) {
            // Best effort: still pending, retried on resume and on focus; the clip is marked sensitive either way.
        }
    }

    private fun isOurs(description: ClipDescription): Boolean =
        description.label?.toString() == PairingClipboard.CLIP_LABEL && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)

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
 * focus goes to it, not to the activity under it. While attached, a gain of focus by THIS window
 * retries a pending clear (see [AndroidPairingClipboard.watchFocus]).
 *
 * r2 (verifier P4-1): the dialog's window goes before its composition is disposed, so the listener
 * comes off on detach, while the view still reaches that window's observer (a dispose-time removal
 * would reach a fresh floating observer instead and leave it on the dead window).
 */
@Composable
fun RetryClipboardClearOnFocus(clipboard: AndroidPairingClipboard) {
    val view = LocalView.current
    DisposableEffect(view, clipboard) {
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = clipboard.watchFocus(v)
            override fun onViewDetachedFromWindow(v: View) = clipboard.unwatchFocus(v)
        }
        view.addOnAttachStateChangeListener(attachment)
        if (view.isAttachedToWindow) clipboard.watchFocus(view)
        onDispose {
            view.removeOnAttachStateChangeListener(attachment)
            clipboard.unwatchFocus(view)
        }
    }
}
