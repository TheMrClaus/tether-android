package com.tether.app.ui.chat

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * ta-coik.58 (#242): where a prose image comes from. A line-for-line port of the web's
 * `resolveImageSrc` / `imageBasename` (components/markdown.tsx 29537e0 :34-77). An agent posts a
 * screenshot as `![alt](target)` and the target is almost always an absolute path on the host's
 * disk; the web serves such a path through `/api/files?path=`, the app does the same over the
 * paired credential ([ToolMediaRepository]). The allowlist is the web's, narrow on purpose:
 *   - http(s) URL                      -> used as is (no credential, no referrer)
 *   - a URL the app already serves     -> passed through unchanged
 *     (`/api/tool-media/<sha256>.<ext>`, `/api/files?path=...`)
 *   - `/abs/path` or `file:///abs/path` -> `/api/files?path=<encoded path>`
 * Everything else (`data:`, `javascript:`, a relative path, `//host/x`) is null: the caller
 * degrades the image to its alt text. JS `/i` without `u` folds ASCII only, so the patterns use an
 * inline `(?i)` (ASCII) and never `RegexOption.IGNORE_CASE` (which also folds Unicode).
 */

private val HTTP_URL = Regex("(?i)^https?://")
private val SERVED_MEDIA_URL = Regex("(?i)^/api/(?:tool-media/[0-9a-f]{64}\\.[a-z0-9]+|files\\?path=[^&#]+)\\z")
private val FILE_URL = Regex("(?i)^file:")
private val FILE_URL_ABS = Regex("(?i)^file:///")
private val SERVED_FILES_PATH = Regex("(?i)^/api/files\\?path=([^&#]+)")

/** The route prefix a served file URL starts with (the web's, lower case). */
internal const val FILES_ROUTE_PREFIX = "/api/files?path="

/** `resolveImageSrc`: the URL to show for [target], or null when it is not an allowed image source. */
internal fun resolveImageSrc(target: String): String? {
    if (HTTP_URL.containsMatchIn(target)) return target
    if (SERVED_MEDIA_URL.containsMatchIn(target)) return target
    var path = target
    if (FILE_URL.containsMatchIn(target)) {
        // Only the host-less `file:///abs` form: `file://host/x` names another machine.
        if (!FILE_URL_ABS.containsMatchIn(target)) return null
        path = decodeUriComponent(target.substring("file://".length)) ?: return null // malformed percent-escape
    }
    if (!path.startsWith("/") || path.startsWith("//")) return null
    return FILES_ROUTE_PREFIX + encodeUriComponent(path)
}

/** The last path segment of an image target, so an image with empty alt text is never unlabelled. */
internal fun imageBasename(target: String): String {
    val served = SERVED_FILES_PATH.find(target)
    val path = if (served != null) {
        decodeUriComponent(served.groupValues[1]) ?: target
    } else {
        target.replace(Regex("[?#].*\\z", RegexOption.DOT_MATCHES_ALL), "")
    }
    return path.split('/').lastOrNull { it.isNotEmpty() } ?: "image"
}

/** The absolute path a served `/api/files?path=<encoded>` URL names (null: not one, or a bad escape). */
internal fun servedFilePath(src: String): String? {
    val m = SERVED_FILES_PATH.find(src) ?: return null
    return decodeUriComponent(m.groupValues[1])
}

private const val URI_UNRESERVED = "-_.!~*'()"
private const val HEX = "0123456789ABCDEF"

/** JS `encodeURIComponent`. */
internal fun encodeUriComponent(value: String): String = buildString {
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF).toChar()
        if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || URI_UNRESERVED.indexOf(c) >= 0) {
            append(c)
        } else {
            append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }
}

/** JS `decodeURIComponent`: null where it throws (a malformed escape, or bytes that are not UTF-8). */
internal fun decodeUriComponent(value: String): String? {
    val out = java.io.ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        if (cp == '%'.code) {
            if (i + 3 > value.length) return null
            val hi = Character.digit(value[i + 1], 16)
            val lo = Character.digit(value[i + 2], 16)
            if (hi < 0 || lo < 0) return null
            out.write(hi * 16 + lo)
            i += 3
        } else {
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
    }
    return strictUtf8(out.toByteArray())
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}
