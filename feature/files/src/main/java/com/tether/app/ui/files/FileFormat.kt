package com.tether.app.ui.files

import com.tether.app.client.WorkspaceFileEntry
import com.tether.app.protocol.helpers.jsToFixed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** workspace-file-browser.tsx PreviewKind. */
enum class PreviewKind { Image, Video, Text, Unsupported }

/** The web's extension tables, verbatim (workspace-file-browser.tsx 31-39). */
object FileKinds {
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif")
    val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov")
    val TEXT_EXTENSIONS = setOf(
        "c", "cc", "conf", "cpp", "css", "csv", "go", "h", "hpp", "html", "htm", "ini",
        "java", "js", "jsx", "json", "log", "md", "mjs", "cjs", "php", "properties", "py",
        "rb", "rs", "sh", "sql", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml",
    )
    val TEXT_FILENAMES = setOf("dockerfile", "license", "makefile", "readme")

    /** web `extension(name)`: after the last dot, lower-cased; "" without one. */
    fun extension(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index > -1) name.substring(index + 1).lowercase(Locale.ROOT) else ""
    }

    /** web `previewKind(entry)`. */
    fun previewKind(name: String): PreviewKind {
        val suffix = extension(name)
        return when {
            suffix in IMAGE_EXTENSIONS -> PreviewKind.Image
            suffix in VIDEO_EXTENSIONS -> PreviewKind.Video
            suffix in TEXT_EXTENSIONS || name.lowercase(Locale.ROOT) in TEXT_FILENAMES -> PreviewKind.Text
            else -> PreviewKind.Unsupported
        }
    }

    fun previewKind(entry: WorkspaceFileEntry): PreviewKind = previewKind(entry.name)

    /** Every image kind the web previews has a native preview: bitmaps are decoded, an SVG is rasterised ([SvgImages]). */
    fun nativeImage(name: String): Boolean = previewKind(name) == PreviewKind.Image

    fun isSvg(name: String): Boolean = previewKind(name) == PreviewKind.Image && extension(name) == "svg"
}

/** The host's locale and time zone for [FileFormat.modified] (the web's `Intl` default). */
data class FileFormatEnv(val locale: Locale = Locale.getDefault(), val zone: ZoneId = ZoneId.systemDefault())

object FileFormat {
    private val UNITS = listOf("KB", "MB", "GB", "TB")

    /** web `formatSize(bytes)`, with JS toFixed rounding. */
    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        var value = bytes / 1024.0
        var unit = UNITS[0]
        var index = 1
        while (index < UNITS.size && value >= 1024) {
            value /= 1024
            unit = UNITS[index]
            index += 1
        }
        return "${if (value >= 10) jsToFixed(value, 0) else jsToFixed(value, 1)} $unit"
    }

    /**
     * web `formatModified(value)`: `Intl.DateTimeFormat(undefined, {dateStyle: "medium",
     * timeStyle: "short"})` — "Jan 1, 2026, 12:00 AM" in en-US. CLDR's narrow no-break space
     * before the day period is written as a plain space, as Chrome renders it.
     */
    fun modified(value: Double, env: FileFormatEnv = FileFormatEnv()): String {
        if (!value.isFinite()) return "—"
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(env.locale).withZone(env.zone)
        return formatter.format(Instant.ofEpochMilli(value.toLong())).replace('\u202F', ' ').replace('\u00A0', ' ')
    }
}

/**
 * A server-supplied file name made safe to use as ONE local file name (a SAF "create document"
 * suggestion or a shared copy's name). The name is untrusted: it may carry separators, `..`,
 * control or invisible characters, or be absurdly long. The result never contains a separator,
 * is never `.`/`..`/empty/hidden, carries no control, bidi or zero-width character, and is at
 * most [MAX_LOCAL_BYTES] bytes of UTF-8 (file systems count bytes, and a CJK or emoji name is
 * three or four a character) without splitting a character; the extension survives.
 */
object LocalNames {
    const val MAX_LOCAL_BYTES = 200
    private const val FALLBACK = "file"

    /** Bidi overrides / isolates / marks can disguise an extension ("txt.exe" shown as "exe.txt"). */
    private fun isBidi(ch: Char) = ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' || ch == '\u061C'

    /** Zero-width characters make two names look alike; they are dropped. */
    private fun isZeroWidth(ch: Char) = ch in '\u200B'..'\u200D' || ch == '\uFEFF'

    fun safe(serverName: String): String {
        // Only the last path segment, whichever separator the server used.
        val last = serverName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in last) {
                when {
                    isZeroWidth(ch) -> Unit
                    ch.code < 0x20 || ch.code == 0x7F || ch == '\u2028' || ch == '\u2029' -> append('_')
                    ch in "<>:\"|?*" -> append('_')
                    isBidi(ch) -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trimStart('.').trimEnd('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK
        if (utf8(cleaned) <= MAX_LOCAL_BYTES) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        val stem = takeBytes(cleaned.substring(0, cleaned.length - ext.length), MAX_LOCAL_BYTES - utf8(ext)).trimEnd('.', ' ')
        return (stem + ext).ifEmpty { FALLBACK }
    }

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8).size

    /** The longest prefix of whole code points that fits in [budget] bytes. */
    private fun takeBytes(text: String, budget: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > budget) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }
}
