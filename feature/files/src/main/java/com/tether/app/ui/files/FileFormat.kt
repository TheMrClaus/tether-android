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

    /**
     * Native divergence: the app has no SVG renderer (a WebView is not allowed, and a script-free
     * SVG rasteriser is a dependency this task does not add), so SVG shows the "no preview here"
     * panel; every other image kind is decoded as a bitmap.
     */
    fun nativeImage(name: String): Boolean = previewKind(name) == PreviewKind.Image && extension(name) != "svg"
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
 * control characters or be absurdly long. The result never contains a separator, is never
 * `.`/`..`/empty/hidden, and is at most [MAX_LOCAL_NAME] UTF-16 units; the extension survives.
 */
object LocalNames {
    const val MAX_LOCAL_NAME = 120
    private const val FALLBACK = "file"

    fun safe(serverName: String): String {
        // Only the last path segment, whichever separator the server used.
        val last = serverName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in last) {
                append(
                    when {
                        ch.code < 0x20 || ch.code == 0x7F -> '_'
                        ch in "<>:\"|?*" -> '_'
                        // Bidi overrides / isolates can disguise an extension ("txt.exe" shown as "exe.txt").
                        ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' -> '_'
                        else -> ch
                    },
                )
            }
        }.trim().trimStart('.').trimEnd('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK
        if (cleaned.length <= MAX_LOCAL_NAME) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        return cleaned.take(MAX_LOCAL_NAME - ext.length).trimEnd('.', ' ') + ext
    }
}
