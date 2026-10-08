package com.tether.app.ui.components

import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 *
 * [clamp] = false is the other half of the same web rule, for a box that has no `-webkit-line-clamp`
 * and no `text-overflow` (the account card's title and its small lines, ta-c3ie): breaks only at
 * opportunities, no line limit, no ellipsis, and a segment wider than the box is drawn whole on its
 * own line and overflows the box unclipped (`overflow-wrap: normal`; a later sibling paints over it).
 * [maxLines] is not read then. The default keeps every existing caller exactly as it was.
 */
@Composable
fun WholeWordText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    clamp: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    // Unclamped: one semantics node carrying the whole text (the lines below may be several Texts).
    val box = if (clamp) modifier else modifier.semantics(mergeDescendants = true) {}
    BoxWithConstraints(box) {
        val width = constraints.maxWidth
        val cap = if (clamp) maxLines else Int.MAX_VALUE
        val lines = remember(text, style, width, cap, measurer) {
            if (width == Constraints.Infinity || breaksOnlyAtOpportunities(measurer, text, style, width, cap)) {
                null
            } else {
                wholeWordLines(text, cap, width.toFloat()) { measurer.measure(it, style, softWrap = false).size.width.toFloat() }
            }
        }
        if (lines == null) {
            if (clamp) Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            else Text(text, style = style, color = color)
        } else if (clamp) {
            Column {
                lines.forEach { Text(it, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            val whole = text
            Column(Modifier.clearAndSetSemantics { this.text = AnnotatedString(whole) }) {
                // Unbounded: Text lays a line out at the box width otherwise, and a segment wider than it is
                // cut there (one line, the rest never drawn). The box keeps the box width; the text overflows it.
                lines.forEach {
                    Text(
                        it, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                        modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                    )
                }
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

/**
 * The offsets where [text] may break (the end counts). The framework's ICU line instance, the rules the
 * text layout itself breaks by (UAX 14: no break inside an address such as `work@example.com`); under
 * Robolectric `java.text.BreakIterator` is the JDK's, whose rules differ, so it is not used here.
 */
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
