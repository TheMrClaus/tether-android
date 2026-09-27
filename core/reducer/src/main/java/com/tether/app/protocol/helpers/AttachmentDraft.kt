package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.util.Base64

/**
 * T2.2: faithful port of the pure half of lib/attachment-draft.ts. `readFileAsBase64` and
 * `prepareAttachmentData` are browser File/canvas I/O and have no pure counterpart here.
 */
object AttachmentDraft {

    // lib/attachment-draft.ts:18
    const val MAX_ATTACHMENTS = 10
    const val MAX_ATTACHMENT_BYTES = 9 * 1024 * 1024 // per file
    const val MAX_ATTACHMENTS_TOTAL_BYTES = 18 * 1024 * 1024

    // lib/attachment-draft.ts:98
    fun humanSize(bytes: Double): String {
        if (bytes < 1024) return "${numberToString(bytes)} B"
        if (bytes < 1024 * 1024) return "${jsToFixed(bytes / 1024, 0)} KB"
        return "${jsToFixed(bytes / (1024 * 1024), 1)} MB"
    }

    // lib/attachment-draft.ts:104
    fun isImageType(mediaType: JsValue?): Boolean = mediaType is JsStr && mediaType.value.startsWith("image/")

    // lib/attachment-draft.ts:115 — UTF-8 (TextEncoder: a lone surrogate encodes as U+FFFD) → base64.
    fun textToBase64(text: String): String {
        val wellFormed = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    wellFormed.append(c).append(text[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> wellFormed.append('�')
                else -> wellFormed.append(c)
            }
            i++
        }
        return Base64.getEncoder().encodeToString(wellFormed.toString().toByteArray(Charsets.UTF_8))
    }

    // lib/attachment-draft.ts:125 — drop the client-only `id`/`size`; null (JS undefined) for no drafts.
    fun toWireAttachments(drafts: JsArr): JsArr? {
        if (drafts.isEmpty()) return null
        return JsArr.of(
            drafts.map { draft ->
                JsObj.of("name" to draft["name"], "mediaType" to draft["mediaType"], "data" to draft["data"])
            },
        )
    }
}
