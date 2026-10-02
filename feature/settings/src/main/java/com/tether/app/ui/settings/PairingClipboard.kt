package com.tether.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
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

        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); the same key is honoured by the platform's own preview on earlier releases' backports. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** The app's [PairingClipboard] over the system clipboard, its timer on the main looper (it outlives the dialog). */
class AndroidPairingClipboard(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clearAfterMs: Long = PairingClipboard.CLEAR_AFTER_MS,
) : PairingClipboard {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private val token = Any()

    override fun copy(code: PairingCode): Boolean {
        val m = manager ?: return false
        val clip = ClipData.newPlainText(PairingClipboard.CLIP_LABEL, code.reveal())
        clip.description.extras = PersistableBundle().apply { putBoolean(PairingClipboard.EXTRA_IS_SENSITIVE, true) }
        return try {
            m.setPrimaryClip(clip)
            val stamp = digest(code.reveal())
            handler.removeCallbacksAndMessages(token)
            handler.postAtTime({ clearIfStamp(stamp) }, token, android.os.SystemClock.uptimeMillis() + clearAfterMs)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun clearIfHolds(code: PairingCode) = clearIfStamp(digest(code.reveal()))

    private fun clearIfStamp(stamp: ByteArray) {
        val m = manager ?: return
        try {
            val description = m.primaryClipDescription ?: return
            if (description.label?.toString() != PairingClipboard.CLIP_LABEL) return
            // Readable while the app has focus; if it is not, the label (ours alone) is enough.
            val text = m.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (text != null && !MessageDigest.isEqual(digest(text), stamp)) return
            if (Build.VERSION.SDK_INT >= 28) m.clearPrimaryClip() else m.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: RuntimeException) {
            // Best effort: the clip is marked sensitive either way.
        }
    }

    private fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}
