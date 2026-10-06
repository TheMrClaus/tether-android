package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.helpers.JS_WS
import com.tether.app.ui.text.SafeHref

/**
 * T6.1 / PLAN D11: a line-for-line Kotlin port of the web's hand-rolled markdown parser
 * (`components/markdown.tsx` at PARITY_BASE, `renderMarkdown` 264-408 and `parseInline` 150-213).
 *
 * Deliberately NOT commonmark-java or org.jetbrains:markdown: the web does not run a CommonMark
 * parser, it runs these regexes, and "same behaviour" means the same regexes. A spec parser would
 * nest lists, apply CommonMark emphasis flanking rules, read indented code blocks, setext
 * headings and HTML, and so disagree with the web on real replies. The web's supported subset is
 * exactly: fenced code (N-backtick, tolerant of a missing closer), ATX headings, unordered and
 * ordered lists (flat), blockquotes, horizontal rules, GFM pipe tables with alignment, paragraphs
 * with soft line breaks, and inline code / links (http(s)/mailto allowlist) / bold / italic.
 * It has NO strikethrough, NO task-list checkboxes and NO syntax highlighting — `~~x~~` and
 * `[x]` stay literal text and a fence is plain monospace (the web reference screenshots show
 * exactly that) — so this port has none either.
 *
 * JS regex semantics are kept where Java's differ: `\s` is the JS whitespace class ([JS_WS]),
 * `.` excludes only the line terminators LF, CR, U+2028 and U+2029, and `$` (no `m` flag) is
 * end-of-input (`\z`), never
 * "before a final line terminator". Case-insensitivity: JS `/i` without `u` folds ASCII only, so
 * never use Kotlin `RegexOption.IGNORE_CASE` (JVM: Unicode folding, `ſ` == `s`) or
 * `ignoreCase = true` for a ported web regex — see [startsWithAsciiIgnoreCase].
 *
 * Web infinite loops the port does not reproduce (see the guard in [parseMarkdown]): a line that
 * passes the heading-START check but fails the full heading regex, because JS `.` cannot cross a
 * lone line terminator inside the line — `"# x\r"` (CR) and `"## title\u2028"` (LINE SEPARATOR).
 *
 * The parse is pure (no Compose): [MdBlock] / [MdInline] are the React nodes the web builds.
 */
@Immutable
sealed interface MdInline {
    /** A literal text leaf (React string child: never re-parsed, never HTML). */
    data class Text(val text: String) : MdInline

    /** `<code className="md-code">` — content literal. */
    data class Code(val text: String) : MdInline

    /** `<a href target=_blank>` — [href] passed [isSafeHref]. */
    data class Link(val href: String, val children: List<MdInline>) : MdInline

    /** A link whose href failed the allowlist: `<span>` of its label, no link. */
    data class Span(val children: List<MdInline>) : MdInline

    /** `<strong>` (`**x**` / `__x__`). */
    data class Strong(val children: List<MdInline>) : MdInline

    /** `<em>` (`*x*` / `_x_`). */
    data class Em(val children: List<MdInline>) : MdInline

    /**
     * ta-coik.58 (#242): `![alt](target)` whose target passed [resolveImageSrc] — the web's
     * `<MarkdownImage src alt>`. [src] is the RESOLVED source (an absolute http(s) URL, a served
     * `/api/tool-media/…` or `/api/files?path=…` URL); [alt] is the trimmed alt text, or the target's
     * file name when it is empty. An unsafe target is a [Span] of its alt text instead.
     */
    data class Image(val src: String, val alt: String) : MdInline
}

/** A table column's alignment from the delimiter row (`null` = default, rendered left). */
enum class MdAlign { Left, Center, Right }

@Immutable
sealed interface MdBlock {
    /** `<p className="md-p">`: [lines] joined by `<br/>` (soft breaks are kept). */
    data class Paragraph(val lines: List<List<MdInline>>) : MdBlock

    /** `h{min(level+2,6)}` — the source `#` count is [level] (1..6); see [tag]. */
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock {
        /** The rendered tag level: chat headings shift down two (`# x` is an h3). */
        val tag: Int get() = minOf(level + 2, 6)
    }

    /** `CodeBlock`: the exact raw fence body (what Copy copies) and the info string. */
    data class Code(val code: String, val lang: String?) : MdBlock

    /** `<blockquote className="md-quote">` holding one paragraph of the quoted lines. */
    data class Quote(val paragraph: Paragraph) : MdBlock

    /** `<ol className="md-list">` (the browser numbers from 1). */
    data class OrderedList(val items: List<List<MdInline>>) : MdBlock

    /** `<ul className="md-list">`. */
    data class BulletList(val items: List<List<MdInline>>) : MdBlock

    /**
     * `.md-table-wrap > table.md-table`. Every body row has exactly `headers.size` cells
     * (missing cells are empty, extra cells dropped — `Array.from({ length: colCount })`).
     */
    data class Table(
        val headers: List<List<MdInline>>,
        val aligns: List<MdAlign?>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock

    /** `<hr className="md-hr">`. */
    data object Rule : MdBlock
}


/** JS `.` (no `s` flag): anything but the four line terminators. */
private const val JS_DOT = "[^\\n\\r\\u2028\\u2029]"

// ── Block regexes (markdown.tsx 280-400) ────────────────────────────────────────────────────
private val FENCE = Regex("^$JS_WS*(`{3,})($JS_DOT*)\\z")
private val BLANK = Regex("^$JS_WS*\\z")
private val HR = Regex("^$JS_WS*([-*_])(?:$JS_WS*\\1){2,}$JS_WS*\\z")
private val HEADING = Regex("^(#{1,6})$JS_WS+($JS_DOT*)\\z")
private val HEADING_START = Regex("^(#{1,6})$JS_WS+")
private val QUOTE = Regex("^$JS_WS*>$JS_WS?")
private val ORDERED = Regex("^$JS_WS*\\d+\\.$JS_WS+")
private val BULLET = Regex("^$JS_WS*[-*+]$JS_WS+")
private val FENCE_START = Regex("^$JS_WS*```")
private val CELL_SPLIT = Regex("(?<!\\\\)\\|")
private val DELIMITER_CELL = Regex("^:?-+:?\\z")

// ── Inline rules, in precedence order (markdown.tsx 158-176) ────────────────────────────────
private val INLINE_CODE = Regex("`([^`\\n]+)`")
/** The JS whitespace class's members, for use inside a negated class (no nested classes). */
private val JS_WS_MEMBERS = JS_WS.removeSurrounding("[", "]")
private val INLINE_LINK = Regex("\\[([^\\]\\n]+)]\\(([^)$JS_WS_MEMBERS]+)\\)")
/** markdown.tsx:243 `!\[([^\]\n]*)\]\(([^)\s]+)\)`: before the link rule, which would match one character later. */
private val INLINE_IMAGE = Regex("!\\[([^\\]\\n]*)]\\(([^)$JS_WS_MEMBERS]+)\\)")
private val INLINE_STRONG = Regex("\\*\\*([^\\n]+?)\\*\\*|__([^\\n]+?)__")
private val INLINE_EM = Regex("\\*([^*\\n]+?)\\*|_([^_\\n]+?)_")

/** markdown.tsx:194 — above this a line is one literal leaf (adversarial-input guard). */
const val INLINE_SCAN_LIMIT: Int = 20_000

/** markdown.tsx:204 — at most this many inline tokens per scan. */
private const val INLINE_GUARD: Int = 5000

/** markdown.tsx:31 `SAFE_HREF = /^(https?:\/\/|mailto:)/i`: the web renderer's scheme prefixes. */
internal val WEB_HREF_SCHEMES: List<String> = listOf("http://", "https://", "mailto:")

/**
 * Is [href] a chat link? Exactly markdown.tsx:31 `SAFE_HREF = /^(https?:\/\/|mailto:)/i`: the href
 * starts with `http://`, `https://` or `mailto:` (ASCII case only), and nothing after the scheme is
 * checked (ta-coik.12: user-info, bidi or invisible characters, any mailto address or a fragment
 * are links on the web, so they are links here; the browser or mail app reads them as it would
 * from the web's `<a>`). The stricter full-string [SafeHref] check stays the SERVICE-link pin
 * (ta-coik.2, ServiceOpenLink in feature/shell); a chat link uses it only to pick the ASCII form
 * it opens in (see [openChatLink]).
 *
 * The scheme fold is NOT a Kotlin `Regex(…, IGNORE_CASE)`: on the JVM that flag also sets
 * UNICODE_CASE, so `ſ` (U+017F) matches `s` and `ı` / `İ` (U+0131 / U+0130) match `i` — `httpſ://x`
 * and `maılto:x` would pass the allowlist and become intents. JS `/i` without the `u` flag folds
 * ASCII only.
 */
fun isSafeHref(href: String): Boolean = WEB_HREF_SCHEMES.any { href.startsWithAsciiIgnoreCase(it) }

/**
 * JS `/^prefix/i` (no `u` flag): only `A`-`Z` fold to `a`-`z`; every other char must equal the
 * [lowercasePrefix] char exactly. Never `startsWith(…, ignoreCase = true)` — that folds Unicode.
 */
internal fun String.startsWithAsciiIgnoreCase(lowercasePrefix: String): Boolean {
    if (length < lowercasePrefix.length) return false
    for (n in lowercasePrefix.indices) {
        val c = this[n]
        val folded = if (c in 'A'..'Z') c + ('a' - 'A') else c
        if (folded != lowercasePrefix[n]) return false
    }
    return true
}

private class InlineMatch(val index: Int, val length: Int, val node: MdInline)

/** `firstInlineMatch`: the earliest match of any rule; ties go to the earlier rule. */
private fun firstInlineMatch(text: String): InlineMatch? {
    var best: InlineMatch? = null
    fun offer(match: MatchResult?, make: (MatchResult) -> MdInline): Boolean {
        if (match == null) return false
        val index = match.range.first
        if (best == null || index < best!!.index) {
            best = InlineMatch(index, match.value.length, make(match))
            if (index == 0) return true // nothing can beat a match at position 0
        }
        return false
    }
    if (offer(INLINE_CODE.find(text)) { MdInline.Code(it.groupValues[1]) }) return best
    if (offer(INLINE_IMAGE.find(text)) { m ->
            val alt = m.groupValues[1]
            val target = m.groupValues[2]
            val src = resolveImageSrc(target)
            // An unsafe target degrades to its alt text (or, with no alt, the literal target: inert text).
            if (src != null) MdInline.Image(src, jsTrim(alt).ifEmpty { imageBasename(target) })
            else MdInline.Span(if (alt.isNotEmpty()) parseInline(alt) else listOf(MdInline.Text(target)))
        }
    ) return best
    if (offer(INLINE_LINK.find(text)) { m ->
            val label = parseInline(m.groupValues[1])
            if (isSafeHref(m.groupValues[2])) MdInline.Link(m.groupValues[2], label) else MdInline.Span(label)
        }
    ) return best
    if (offer(INLINE_STRONG.find(text)) { m -> MdInline.Strong(parseInline(group1or2(m))) }) return best
    offer(INLINE_EM.find(text)) { m -> MdInline.Em(parseInline(group1or2(m))) }
    return best
}

/** `m[1] ?? m[2]`: the alternative that matched. */
private fun group1or2(m: MatchResult): String = m.groups[1]?.value ?: m.groups[2]?.value.orEmpty()

/** `parseInline`: [text] as inline nodes, left to right. */
fun parseInline(text: String): List<MdInline> {
    if (text.length > INLINE_SCAN_LIMIT) return listOf(MdInline.Text(text))
    val nodes = ArrayList<MdInline>()
    var rest = text
    var guard = 0
    while (rest.isNotEmpty() && guard++ < INLINE_GUARD) {
        val match = firstInlineMatch(rest) ?: break
        if (match.index > 0) nodes.add(MdInline.Text(rest.substring(0, match.index)))
        nodes.add(match.node)
        rest = rest.substring(match.index + match.length)
    }
    if (rest.isNotEmpty()) nodes.add(MdInline.Text(rest))
    return nodes
}

private fun paragraph(lines: List<String>): MdBlock.Paragraph = MdBlock.Paragraph(lines.map(::parseInline))

/** `splitTableRow` (markdown.tsx:235): optional outer pipes, `\|` escapes a pipe inside a cell. */
fun splitTableRow(line: String): List<String> {
    var s = jsTrim(line)
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split(CELL_SPLIT).map { jsTrim(it).replace("\\|", "|") }
}

/** `isTableDelimiterRow` (markdown.tsx:244). */
fun isTableDelimiterRow(line: String): Boolean {
    if (!line.contains("|")) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { DELIMITER_CELL.containsMatchIn(it) }
}

/** `tableAlignments` (markdown.tsx:251). */
fun tableAlignments(line: String): List<MdAlign?> = splitTableRow(line).map { cell ->
    when {
        cell.startsWith(":") && cell.endsWith(":") -> MdAlign.Center
        cell.startsWith(":") -> MdAlign.Left
        cell.endsWith(":") -> MdAlign.Right
        else -> null
    }
}

/**
 * `renderMarkdown` (markdown.tsx:264): the block parse. Tolerates partial markdown (an
 * unclosed fence collects to the end) and never throws.
 */
fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // Fenced code: N-backtick fences, closed by a line of >= N backticks; else runs to EOF.
        val fence = FENCE.find(line)
        if (fence != null) {
            val fenceLen = fence.groupValues[1].length
            val lang = jsTrim(fence.groupValues[2])
            val body = ArrayList<String>()
            i++
            val closer = Regex("^$JS_WS*`{$fenceLen,}$JS_WS*\\z")
            while (i < lines.size && !closer.containsMatchIn(lines[i])) body.add(lines[i++])
            if (i < lines.size) i++ // consume the closing fence
            blocks.add(MdBlock.Code(body.joinToString("\n"), lang.ifEmpty { null }))
            continue
        }

        if (BLANK.containsMatchIn(line)) {
            i++
            continue
        }

        if (HR.containsMatchIn(line)) {
            blocks.add(MdBlock.Rule)
            i++
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
            i++
            continue
        }

        if (QUOTE.containsMatchIn(line)) {
            val quoted = ArrayList<String>()
            while (i < lines.size && QUOTE.containsMatchIn(lines[i])) quoted.add(QUOTE.replaceFirst(lines[i++], ""))
            blocks.add(MdBlock.Quote(paragraph(quoted)))
            continue
        }

        if (ORDERED.containsMatchIn(line)) {
            val items = ArrayList<List<MdInline>>()
            while (i < lines.size && ORDERED.containsMatchIn(lines[i])) items.add(parseInline(ORDERED.replaceFirst(lines[i++], "")))
            blocks.add(MdBlock.OrderedList(items))
            continue
        }

        if (BULLET.containsMatchIn(line)) {
            val items = ArrayList<List<MdInline>>()
            while (i < lines.size && BULLET.containsMatchIn(lines[i])) items.add(parseInline(BULLET.replaceFirst(lines[i++], "")))
            blocks.add(MdBlock.BulletList(items))
            continue
        }

        // GFM table: a pipe header row immediately followed by a dash delimiter row; body rows
        // continue until a blank or a pipe-less line.
        if (line.contains("|") && i + 1 < lines.size && isTableDelimiterRow(lines[i + 1])) {
            val headers = splitTableRow(line)
            val aligns = tableAlignments(lines[i + 1])
            val colCount = headers.size
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].contains("|") && !BLANK.containsMatchIn(lines[i])) {
                rows.add(splitTableRow(lines[i]))
                i++
            }
            blocks.add(
                MdBlock.Table(
                    headers = headers.map(::parseInline),
                    aligns = List(colCount) { n -> aligns.getOrNull(n) },
                    rows = rows.map { row -> List(colCount) { n -> parseInline(row.getOrNull(n) ?: "") } },
                ),
            )
            continue
        }

        // Paragraph: consecutive lines until a blank line or a block starter.
        val para = ArrayList<String>()
        while (
            i < lines.size &&
            !BLANK.containsMatchIn(lines[i]) &&
            !FENCE_START.containsMatchIn(lines[i]) &&
            !HEADING_START.containsMatchIn(lines[i]) &&
            !QUOTE.containsMatchIn(lines[i]) &&
            !ORDERED.containsMatchIn(lines[i]) &&
            !BULLET.containsMatchIn(lines[i]) &&
            !HR.containsMatchIn(lines[i])
        ) {
            para.add(lines[i++])
        }
        // Divergence guard (documented): a line like "# x\r" or "## title\u2028" (a lone CR or
        // U+2028, which JS `.` cannot cross; `split("\n")` leaves it inside the line) fails the
        // full heading regex but passes the heading-start check, so the web's
        // loop pushes an empty paragraph without advancing and never terminates. Here the line is
        // consumed as a one-line paragraph instead.
        if (para.isEmpty()) para.add(lines[i++])
        blocks.add(paragraph(para))
    }
    return blocks
}

/** The plain text of inline nodes (a link's label, never its URL) — TalkBack and tests. */
fun List<MdInline>.plainText(): String = buildString { appendPlain(this@plainText) }

private fun StringBuilder.appendPlain(nodes: List<MdInline>) {
    for (node in nodes) when (node) {
        is MdInline.Text -> append(node.text)
        is MdInline.Code -> append(node.text)
        is MdInline.Link -> appendPlain(node.children)
        is MdInline.Span -> appendPlain(node.children)
        is MdInline.Strong -> appendPlain(node.children)
        is MdInline.Em -> appendPlain(node.children)
        is MdInline.Image -> append(node.alt)
    }
}
