package com.tether.app.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale

/**
 * Text that wraps in whole words and clamps to [maxLines] lines with an ellipsis: the web's
 * `white-space: normal; overflow-wrap: normal; word-break: normal; -webkit-line-clamp: N;
 * text-overflow: ellipsis` (globals.css:10917-10923, ta-z4c1; both break properties compute to
 * `normal` on every box of the session row's chain).
 *
 * - Lines break only at line-break opportunities (spaces, after a hyphen).
 * - A segment wider than the box takes a line of its own, cut at the box edge with "…". It is never
 *   split: Android's LineBreaker would otherwise make a desperate break inside it, a mid-word split
 *   the web never shows.
 * - The last line ends in "…" when text remains.
 *
 * Every line end of the ordinary layout is checked; when each is an opportunity (the common case)
 * this is the plain `Text(maxLines, Ellipsis)`, pixel for pixel. Only a layout with a desperate
 * break is replaced, by [wholeWordLines] drawn one line each.
 */
@Composable
fun WholeWordText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val lines = remember(text, style, width, maxLines, measurer) {
            if (width == Constraints.Infinity || breaksOnlyAtOpportunities(measurer, text, style, width, maxLines)) {
                null
            } else {
                wholeWordLines(text, maxLines, width.toFloat()) { measurer.measure(it, style, softWrap = false).size.width.toFloat() }
            }
        }
        if (lines == null) {
            Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        } else {
            Column {
                lines.forEach { Text(it, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** True when every line end that is followed by another displayed line is a line-break opportunity. */
private fun breaksOnlyAtOpportunities(measurer: TextMeasurer, text: String, style: TextStyle, width: Int, maxLines: Int): Boolean {
    if (text.isEmpty()) return true
    val layout = measurer.measure(text, style, softWrap = true, maxLines = Int.MAX_VALUE, constraints = Constraints(maxWidth = width))
    val boundaries = lineBoundaries(text)
    return (0 until minOf(maxLines, layout.lineCount) - 1).all { layout.getLineEnd(it) in boundaries }
}

/** The offsets where [text] may break (ICU line instance; the end counts). */
internal fun lineBoundaries(text: String): Set<Int> {
    val it = BreakIterator.getLineInstance(Locale.ROOT)
    it.setText(text)
    val out = HashSet<Int>()
    var b = it.first()
    while (b != BreakIterator.DONE) {
        out += b
        b = it.next()
    }
    return out
}

/**
 * The lines of [text] laid out greedily by whole segment into at most [maxLines] lines of
 * [maxWidth]: line k takes segments while they fit ([widthOf] measures one unwrapped line); a
 * segment wider than the box takes a line of its own; the last line holds everything left, to be
 * cut at the box edge with "…". Trailing blanks are dropped.
 */
internal fun wholeWordLines(text: String, maxLines: Int, maxWidth: Float, widthOf: (String) -> Float): List<String> {
    val bounds = lineBoundaries(text).sorted()
    val segments = bounds.zipWithNext { a, b -> text.substring(a, b) }
    val lines = ArrayList<String>()
    var i = 0
    while (i < segments.size && lines.size < maxLines - 1) {
        var line = segments[i++]
        while (i < segments.size && widthOf((line + segments[i]).trimEnd()) <= maxWidth) line += segments[i++]
        lines += line.trimEnd()
    }
    if (i < segments.size) lines += segments.subList(i, segments.size).joinToString("").trimEnd()
    return lines
}
