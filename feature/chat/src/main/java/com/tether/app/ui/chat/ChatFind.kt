package com.tether.app.ui.chat

import com.tether.app.protocol.fold.jsTrim
import com.tether.app.protocol.model.SessionProjection
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.protocol.model.Vocab
import java.util.IdentityHashMap
import java.util.Locale

/**
 * T5.3: the in-chat find (chat-view.tsx 2013-2120, issues #12 / #174) — the pure half.
 *
 * The unit of a match is an OCCURRENCE, addressed as (turn, block, ordinal within the block).
 * Occurrences are counted by the SAME leaf walk the renderer paints ([MarkdownBody] with a
 * [FindMarks]): markdown for a finished reply, plain text for a user bubble or a still-streaming
 * reply, so the bar's "N of M" and the marks on screen can never disagree. Only chat text (user
 * and assistant messages) is searched — not tool calls, thinking or command output.
 */

/** The find needle: `findQuery.trim().toLowerCase()` (chat-view.tsx:2027). */
internal fun findNeedle(query: String): String = jsTrim(query).lowercase(Locale.ROOT)

/** chat-view.tsx:2031 — a needle shorter than this marks nothing. */
internal const val FIND_MIN_NEEDLE = 2

/**
 * JS `/x/gi` without the `u` flag (markdown.tsx:101 `createFindHighlighter`): each UTF-16 unit
 * is canonicalised by `toUpperCase`, unless that yields more than one unit, or maps a non-ASCII
 * unit onto ASCII (ES Canonicalize, non-unicode mode) — so `ſ` does not match `s`.
 */
private fun canonicalize(c: Char): Char {
    val upper = c.toString().uppercase(Locale.ROOT)
    if (upper.length != 1) return c
    val cu = upper[0]
    if (c.code >= 128 && cu.code < 128) return c
    return cu
}

/**
 * Every occurrence of [needle] in [text]: case-insensitive, non-overlapping, left to right — the
 * one rule for the count and for the marks (markdown.tsx:99-133). Empty [needle]: none.
 */
internal fun findRanges(text: String, needle: String): List<IntRange> {
    val n = needle.length
    if (n == 0 || text.length < n) return emptyList()
    val pattern = CharArray(n) { canonicalize(needle[it]) }
    val out = ArrayList<IntRange>()
    var i = 0
    while (i <= text.length - n) {
        var k = 0
        while (k < n && canonicalize(text[i + k]) == pattern[k]) k++
        if (k == n) {
            out += i until i + n
            i += n
        } else {
            i++
        }
    }
    return out
}

/** markdown.tsx:143 `countPlainMatches`. */
internal fun countPlainMatches(text: String, needle: String): Int = findRanges(text, needle).size

/** The marks [inlineAnnotated] paints over [nodes], in document order (links: their label only). */
internal fun countInlineMatches(nodes: List<MdInline>, needle: String): Int = nodes.sumOf { node ->
    when (node) {
        is MdInline.Text -> countPlainMatches(node.text, needle)
        is MdInline.Code -> countPlainMatches(node.text, needle)
        is MdInline.Link -> countInlineMatches(node.children, needle)
        is MdInline.FileLink -> countInlineMatches(node.children, needle)
        is MdInline.Span -> countInlineMatches(node.children, needle)
        is MdInline.Strong -> countInlineMatches(node.children, needle)
        is MdInline.Em -> countInlineMatches(node.children, needle)
        // markdown.tsx: the alt text is an attribute, never a text leaf: not marked.
        is MdInline.Image -> 0
    }
}

/** The marks one markdown block paints (a fence: its raw body; a table: header cells, then rows). */
internal fun countBlockMatches(block: MdBlock, needle: String): Int = when (block) {
    is MdBlock.Paragraph -> block.lines.sumOf { countInlineMatches(it, needle) }
    is MdBlock.Heading -> countInlineMatches(block.inlines, needle)
    is MdBlock.Code -> countPlainMatches(block.code, needle)
    is MdBlock.Quote -> block.paragraph.lines.sumOf { countInlineMatches(it, needle) }
    is MdBlock.OrderedList -> block.items.sumOf { countInlineMatches(it, needle) }
    is MdBlock.BulletList -> block.items.sumOf { countInlineMatches(it, needle) }
    is MdBlock.Table -> block.headers.sumOf { countInlineMatches(it, needle) } +
        block.rows.sumOf { row -> row.sumOf { countInlineMatches(it, needle) } }
    MdBlock.Rule -> 0
}

/** markdown.tsx:411 `countMarkdownMatches`: a match hidden in a link URL or markup is not one. */
internal fun countMarkdownMatches(text: String, needle: String): Int = parseMarkdown(text).sumOf { countBlockMatches(it, needle) }

/**
 * chat-view.tsx:522-531 `countFindOccurrences`: only user and assistant messages; a finished
 * reply through the markdown path, anything else as plain text; 0 without a (lowercased)
 * substring hit, before any parse.
 */
internal fun countFindOccurrences(block: TurnBlock, needle: String): Int {
    if (block.kind != Vocab.BLOCK_USER_MESSAGE && block.kind != Vocab.BLOCK_MESSAGE) return 0
    val text = block.text.orEmpty()
    if (text.isEmpty() || !text.lowercase(Locale.ROOT).contains(needle)) return 0
    return if (block.kind == Vocab.BLOCK_MESSAGE && block.done == true) countMarkdownMatches(text, needle) else countPlainMatches(text, needle)
}

/**
 * Per-block counts cached on block identity (the web's `WeakMap<TurnBlock>`): the fold replaces
 * only the block an event touches, so a streaming delta recounts one block, not the transcript.
 */
internal class FindCounter {
    private var needle: String = ""
    private val cache = IdentityHashMap<TurnBlock, Int>()

    fun count(block: TurnBlock, needle: String): Int {
        if (needle != this.needle) {
            this.needle = needle
            cache.clear()
        }
        return cache.getOrPut(block) { countFindOccurrences(block, needle) }
    }
}

/** One occurrence: the [ordinal]-th mark painted in (turn, block). */
internal data class FindHit(val turnId: String, val blockId: String, val ordinal: Int)

internal class FindResults(val hits: List<FindHit>, val turnCount: Int, val blocksWithHits: Set<String>) {
    companion object {
        val Empty = FindResults(emptyList(), 0, emptySet())
    }
}

internal fun findBlockKey(turnId: String, blockId: String): String = "$turnId\u0000$blockId"

/**
 * chat-view.tsx:2028-2049: every occurrence in document order (`turnOrder`, then each turn's
 * `blocks`), how many turns hold one, and the blocks with marks to paint. A closed bar or a
 * needle under two characters finds nothing (every mark disappears with it).
 */
internal fun findResults(
    projection: SessionProjection?,
    needle: String,
    open: Boolean,
    counter: FindCounter = FindCounter(),
): FindResults {
    if (!open || projection == null || needle.length < FIND_MIN_NEEDLE) return FindResults.Empty
    val hits = ArrayList<FindHit>()
    val blocks = HashSet<String>()
    var turns = 0
    for (turnId in projection.turnOrder) {
        val turn = projection.turnsById[turnId] ?: continue
        var turnHit = false
        for (blockId in turn.blocks) {
            val block = turn.blocksById[blockId] ?: continue
            val count = counter.count(block, needle)
            if (count == 0) continue
            turnHit = true
            blocks += findBlockKey(turnId, blockId)
            for (ordinal in 0 until count) hits += FindHit(turnId, blockId, ordinal)
        }
        if (turnHit) turns++
    }
    return FindResults(hits, turns, blocks)
}

/** chat-view.tsx:2054 — the active index clamped at read time (a shrinking set never points out of range). */
internal fun activeHitIndex(results: FindResults, index: Int): Int =
    if (results.hits.isEmpty()) -1 else minOf(index, results.hits.size - 1)

/** chat-view.tsx:2057-2065 — which of the matching turns holds the active occurrence (1-based). */
internal fun activeTurnOrdinal(results: FindResults, active: Int): Int {
    if (active < 0) return 0
    var ordinal = 0
    var last: String? = null
    for (i in 0..active) {
        val turnId = results.hits[i].turnId
        if (turnId != last) {
            ordinal++
            last = turnId
        }
    }
    return ordinal
}

/** chat-view.tsx:2084-2088 `stepMatch`: from the CLAMPED index, wrapping both ways. */
internal fun stepMatch(results: FindResults, index: Int, delta: Int): Int {
    val size = results.hits.size
    if (size == 0) return index
    val active = activeHitIndex(results, index)
    return ((active + delta) % size + size) % size
}

/** chat-view.tsx:3282-3292 — "", "No matches", or "3 of 12" with " · turn 2 of 5". */
internal data class FindCount(val primary: String, val turn: String)

internal fun findCount(needle: String, results: FindResults, index: Int): FindCount {
    if (needle.length < FIND_MIN_NEEDLE) return FindCount("", "")
    if (results.hits.isEmpty()) return FindCount("No matches", "")
    val active = activeHitIndex(results, index)
    return FindCount("${active + 1} of ${results.hits.size}", " · turn ${activeTurnOrdinal(results, active)} of ${results.turnCount}")
}

/**
 * What one block paints (chat-view.tsx:2076-2079 `findPropsFor`): the needle only on a block
 * that has marks, so every other block's row stays equal and is skipped; [active] is the ordinal,
 * within this block, of the occurrence the bar is on (-1: none here).
 */
data class FindMarks(val needle: String, val active: Int)

internal fun findMarksFor(results: FindResults, needle: String, activeHit: FindHit?, turnId: String, blockId: String): FindMarks? {
    if (findBlockKey(turnId, blockId) !in results.blocksWithHits) return null
    val active = if (activeHit != null && activeHit.turnId == turnId && activeHit.blockId == blockId) activeHit.ordinal else -1
    return FindMarks(needle, active)
}
