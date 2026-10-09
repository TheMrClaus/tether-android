package com.tether.app.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ta-9jnm: a file the agent names in its prose can be tapped to open the file viewer.
 *
 * [cwd] is the session's working directory (the base of a relative inline-code path); [open] shows
 * one absolute path in the host's file browser. The host (the shell) provides it through
 * [LocalWorkspaceFileOpener]; with none provided nothing is drawn as a link.
 */
class WorkspaceFileLinks(val cwd: String, val open: (String) -> Unit)

/** The host's file opener for a message's file mentions; null: no link is ever drawn. */
val LocalWorkspaceFileOpener = staticCompositionLocalOf<WorkspaceFileLinks?> { null }

/** One drawn link inside a text run: the UTF-16 range [start, end) of [text] and the absolute [path] it opens. */
internal data class FileLinkRange(val start: Int, val end: Int, val path: String)

/** Where a candidate token came from: a run of plain prose, or a whole inline-code span. */
internal enum class FileLinkSource { Text, Code }

/**
 * ta-9jnm: the detector for a file path in agent prose (the extension lists are public so the shell can check them against the file browser's). Pure and lexical: no network, no file system.
 * A path is only ever a candidate; the server decides at tap time (its words are shown verbatim).
 */
object FileLinks {
    /** At most this many links are drawn in one run of prose. */
    internal const val MAX_LINKS = 100

    private const val MAX_TOKEN = 1024

    /** The extensions a mention must end in (lowercase): the files module's preview kinds plus common source and data files. */
    val ALLOW: Set<String> = setOf(
        // images, video, text: the same sets the file browser previews (FileKinds), checked by a shell test
        "png", "jpg", "jpeg", "webp", "gif", "svg", "avif",
        "mp4", "webm", "mov",
        "c", "cc", "conf", "cpp", "css", "csv", "go", "h", "hpp", "html", "htm", "ini",
        "java", "js", "jsx", "json", "log", "md", "mjs", "cjs", "php", "properties", "py",
        "rb", "rs", "sh", "sql", "toml", "ts", "tsx", "txt", "xml", "yaml", "yml",
        // more source, config and artefact kinds
        "kt", "kts", "gradle", "swift", "cs", "lua", "mdx", "jsonc", "lock", "env", "proto", "graphql", "gql",
        "vue", "svelte", "scss", "sass", "less", "zsh", "bash", "fish", "mts", "cts", "cxx", "dart", "scala",
        "ex", "exs", "hs", "ml", "r", "pl", "bat", "ps1", "tsv", "ipynb", "pdf", "zip", "tar", "gz", "tgz",
        "apk", "aab", "jar", "wasm", "db", "sqlite", "patch", "diff", "output",
    )

    /** Extension-less file names a mention may be (lowercase). */
    val NAMES: Set<String> = setOf("dockerfile", "license", "makefile", "readme", "gemfile", "procfile", "gradlew")

    private const val LEADING = "([{<\"'`"
    private const val TRAILING = ".,;:!?\"'`>]}…"
    private const val REJECT_CHARS = "?#*<>|\\"

    /** `:N` `:N:M` `:N-M` `#LN` `#LN-LM` `#LNCM` `(N)` `(N,M)`: one, at the very end. */
    private val LINE_SUFFIX = Regex("(?::\\d+(?::\\d+|-\\d+)?|#L\\d+(?:C\\d+)?(?:-L?\\d+(?:C\\d+)?)?|\\(\\d+(?:,\\d+)?\\))\\z")

    /**
     * The links in [text]. [Text][FileLinkSource.Text]: every space, tab, line-feed or no-break-space
     * separated token. [Code][FileLinkSource.Code]: the whole trimmed span. A relative path
     * only links from inline code, and only when [cwd] is known.
     */
    internal fun detect(text: String, source: FileLinkSource, cwd: String, limit: Int = MAX_LINKS): List<FileLinkRange> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<FileLinkRange>()
        when (source) {
            FileLinkSource.Code -> {
                var s = 0
                var e = text.length
                while (s < e && text[s].isWhitespace()) s++
                while (e > s && text[e - 1].isWhitespace()) e--
                analyse(text, s, e, source, cwd)?.let(out::add)
            }
            FileLinkSource.Text -> {
                var i = 0
                while (i < text.length && out.size < limit) {
                    while (i < text.length && isSeparator(text[i])) i++
                    val s = i
                    while (i < text.length && !isSeparator(text[i])) i++
                    if (i > s) analyse(text, s, i, source, cwd)?.let(out::add)
                }
            }
        }
        return out
    }

    private fun isSeparator(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u00A0'

    /**
     * An href from a markdown link: `/abs/path` or `file:///abs/path` (percent-encoded), any path (the
     * author chose it; a folder opens as a folder). Returns the resolved absolute path, or null.
     */
    internal fun hrefPath(href: String): String? {
        var s = 0
        var e = href.length
        while (s < e && href[s] in LEADING) s++
        e = stripTrailing(href, s, e)
        var body = href.substring(s, e)
        if (body.length > MAX_TOKEN) return null
        if (body.startsWith("file://")) body = body.removePrefix("file://")
        if (!body.startsWith("/")) return null
        body = percentDecode(body) ?: return null
        val path = body.removeSuffixLine()
        if (rejected(path)) return null
        return normalize(path)
    }

    private fun analyse(text: String, from: Int, to: Int, source: FileLinkSource, cwd: String): FileLinkRange? {
        var s = from
        while (s < to && text[s] in LEADING) s++
        val e = stripTrailing(text, s, to)
        if (e <= s || e - s > MAX_TOKEN) return null
        val token = text.substring(s, e)
        val path = token.removeSuffixLine()
        if (rejected(path)) return null
        val resolved: String = if (path.startsWith("/")) {
            if (path.endsWith("/")) return null
            if (path.split('/').count { it.isNotEmpty() } < 2) return null
            if (!knownFile(path)) return null
            normalize(path) ?: return null
        } else {
            if (source != FileLinkSource.Code || cwd.isEmpty() || !cwd.startsWith("/")) return null
            if (path.endsWith("/") || !knownFile(path)) return null
            normalize("$cwd/$path") ?: return null
        }
        return FileLinkRange(s, e, resolved)
    }

    /** [from, to) with the closing punctuation a sentence leaves behind removed; a ")" only when unmatched. */
    private fun stripTrailing(text: String, from: Int, to: Int): Int {
        var e = to
        while (e > from) {
            val c = text[e - 1]
            if (c in TRAILING) {
                e--
            } else if (c == ')') {
                var close = 0
                var open = 0
                for (n in from until e) {
                    if (text[n] == ')') close++ else if (text[n] == '(') open++
                }
                if (close > open) e-- else break
            } else {
                break
            }
        }
        return e
    }

    private fun String.removeSuffixLine(): String {
        val m = LINE_SUFFIX.find(this) ?: return this
        return substring(0, m.range.first)
    }

    private fun rejected(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_TOKEN) return true
        if (path.contains("://") || path.startsWith("//")) return true
        if (path[0] == '~' || path[0] == '-') return true
        var i = 0
        while (i < path.length) {
            val cp = path.codePointAt(i)
            if ((cp < 0x10000 && cp.toChar() in REJECT_CHARS) || hidden(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** A control, bidi, invisible, separator or unassigned code point: never part of a path the reader can see. */
    private fun hidden(cp: Int): Boolean {
        if (cp == ' '.code) return false // spaces inside a code span are allowed
        if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true
        return when (Character.getType(cp)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt() -> true
            else -> false
        }
    }

    private fun knownFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return false
        val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return ext in ALLOW || name.lowercase(java.util.Locale.ROOT) in NAMES
    }

    /** `.` and `..` folded, empty segments dropped; null when `..` climbs out of the root. */
    internal fun normalize(path: String): String? {
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** `%XX` UTF-8 decoding; a bad escape (or invalid UTF-8) is null. */
    private fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.write(b.toInt())
                i++
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
