package com.tether.app.share

import android.content.Intent
import android.net.Uri

/**
 * T11.2: what another app shared into Tether through the system share sheet: its text
 * ([Intent.EXTRA_TEXT]) and its files ([Intent.EXTRA_STREAM], or the clip's items when a sender put
 * them only there). [type] is the share's MIME type when it names one exact type (a hint for a
 * provider that does not say; the bytes still decide).
 */
data class SharePayload(val text: String?, val streams: List<Uri>, val type: String?) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && streams.isEmpty()
}

object ShareIntents {
    /**
     * The share [intent] carries, or null when it is not `ACTION_SEND` / `ACTION_SEND_MULTIPLE` or
     * carries nothing usable. Every extra is untrusted: a wrong type or an unparcelable value reads
     * as absent. The URIs are only collected here; which of them may be read is the attachment
     * intake's rule (another app's `content://` provider only).
     */
    fun parse(intent: Intent?): SharePayload? {
        intent ?: return null
        val multiple = when (intent.action) {
            Intent.ACTION_SEND -> false
            Intent.ACTION_SEND_MULTIPLE -> true
            else -> return null
        }
        val text = guarded { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
            ?: guarded { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.filterNotNull()?.joinToString("\n") }
        val streams = LinkedHashSet<Uri>()
        if (multiple) {
            guarded { intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }?.filterNotNull()?.let(streams::addAll)
        } else {
            guarded { intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }?.let(streams::add)
        }
        if (streams.isEmpty()) {
            val clip = guarded { intent.clipData }
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(streams::add)
        }
        val type = intent.type?.lowercase()?.takeIf { '*' !in it && '/' in it }
        val payload = SharePayload(text?.takeIf { it.isNotEmpty() }, streams.toList(), type)
        return payload.takeUnless { it.isEmpty }
    }

    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (_: RuntimeException) {
        null
    }
}
