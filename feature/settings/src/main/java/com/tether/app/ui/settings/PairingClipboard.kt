package com.tether.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.tether.app.client.PairingCode

/**
 * T10.4: where "Copy code" puts the pairing code on screen. ta-coik.15: as on the web
 * (paired-devices.tsx copyCode: `navigator.clipboard.writeText`), the code is written and the
 * clipboard is then left alone: no timed clear, no clear on expiry or on closing Settings. The clip
 * is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), a platform hint that keeps it out of
 * the system's paste preview and clipboard overlay; it restricts nothing. Nothing is logged.
 */
interface PairingClipboard {
    /** Put [code] on the clipboard (sensitive); false when there is no clipboard or the write failed. */
    fun copy(code: PairingCode): Boolean

    /** No clipboard (previews, goldens): nothing is ever copied. */
    object None : PairingClipboard {
        override fun copy(code: PairingCode) = false
    }

    companion object {
        const val CLIP_LABEL = "Tether pairing code"

        /** The platform's own key (API 33; minSdk is above it). */
        const val EXTRA_IS_SENSITIVE = android.content.ClipDescription.EXTRA_IS_SENSITIVE
    }
}

/** The app's [PairingClipboard] over the system clipboard. */
class AndroidPairingClipboard(private val manager: ClipboardManager?) : PairingClipboard {
    constructor(context: Context) : this(context.applicationContext.getSystemService(ClipboardManager::class.java))

    override fun copy(code: PairingCode): Boolean {
        val m = manager ?: return false
        val clip = ClipData.newPlainText(PairingClipboard.CLIP_LABEL, code.reveal())
        clip.description.extras = PersistableBundle().apply { putBoolean(PairingClipboard.EXTRA_IS_SENSITIVE, true) }
        return try {
            m.setPrimaryClip(clip)
            true
        } catch (_: RuntimeException) {
            // As the web's catch: the code stays on screen to read.
            false
        }
    }
}
