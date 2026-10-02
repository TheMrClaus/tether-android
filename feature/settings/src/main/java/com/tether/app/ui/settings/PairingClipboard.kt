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
    /** Put [code] on the clipboard (sensitive); false when there is no clipboard. */
    fun copy(code: PairingCode): Boolean

    /** Take [code] off the clipboard if it is still the clip there. */
    fun clearIfHolds(code: PairingCode)

    /** No clipboard (previews, goldens): nothing is ever copied. */
    object None : PairingClipboard {
        override fun copy(code: PairingCode) = false
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
 * gives a backgrounded app no clipboard: the description and the clip read as null) comes back null,
 * never as "not ours".
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
 * the dialog).
 *
 * r2 (security F1): Android 10+ lets only the app in focus read the clipboard, so a clear due while
 * Tether is in the background (the 30 s timer, an expiry, a close) cannot tell whether the clip is
 * still ours. It is then NOT done blindly: the copy's stamp stays pending and the clear runs again on
 * the next resume once its deadline has passed ([onResume], wired by [forApp] to every activity
 * resume). A clip is cleared only when it can be read and is positively this copy (its label and its
 * text's SHA-256); anything else is never touched.
 */
class AndroidPairingClipboard(
    private val access: ClipAccess?,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clearAfterMs: Long = PairingClipboard.CLEAR_AFTER_MS,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
) : PairingClipboard {
    constructor(context: Context) : this(context.applicationContext.getSystemService(ClipboardManager::class.java)?.let(ClipAccess::System))

    private val token = Any()

    /** The copy still on the clipboard as far as known: its stamp and when it is due off (uptime). */
    private class Pending(val stamp: ByteArray, var dueAt: Long)

    private var pending: Pending? = null

    override fun copy(code: PairingCode): Boolean {
        val a = access ?: return false
        val clip = ClipData.newPlainText(PairingClipboard.CLIP_LABEL, code.reveal())
        clip.description.extras = PersistableBundle().apply { putBoolean(PairingClipboard.EXTRA_IS_SENSITIVE, true) }
        return try {
            a.set(clip)
            pending = Pending(digest(code.reveal()), uptime() + clearAfterMs)
            handler.removeCallbacksAndMessages(token)
            handler.postAtTime({ attempt() }, token, SystemClock.uptimeMillis() + clearAfterMs)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The code expired or Settings closed: if [code] is the copy still pending, it is due off now. */
    override fun clearIfHolds(code: PairingCode) {
        val p = pending ?: return
        if (!MessageDigest.isEqual(p.stamp, digest(code.reveal()))) return
        p.dueAt = minOf(p.dueAt, uptime())
        attempt()
    }

    /** An activity of the app resumed (it has focus again): a clear that came due meanwhile runs now. */
    fun onResume() {
        val p = pending ?: return
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
            if (label == null || text == null) return // unreadable (background): retried on the next resume
            pending = null
            if (label.toString() != PairingClipboard.CLIP_LABEL) return // something else was copied since
            if (!MessageDigest.isEqual(digest(text.toString()), p.stamp)) return
            a.clear()
            handler.removeCallbacksAndMessages(token)
        } catch (_: RuntimeException) {
            // Best effort: still pending, retried on the next resume; the clip is marked sensitive either way.
        }
    }

    private fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    companion object {
        @Volatile private var app: AndroidPairingClipboard? = null

        /** The one clipboard of the process, its clears retried on every activity resume (security F1). */
        fun forApp(context: Context): AndroidPairingClipboard = app ?: synchronized(this) {
            app ?: AndroidPairingClipboard(context).also { clip ->
                (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ResumeHook(clip))
                app = clip
            }
        }
    }

    private class ResumeHook(private val clip: AndroidPairingClipboard) : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = clip.onResume()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
