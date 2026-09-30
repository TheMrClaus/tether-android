package com.tether.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * A parting line between two molded parts (globals.css 9013-9019, 10850-10874): the upper part's
 * `1px solid var(--line-strong)` edge, then `0 1px 0 var(--seam-lip)` — the lit lip of the
 * surface below. Vertical seams (rail / inspector) put the lip on the inner side. Studio draws a
 * plain `1px var(--line)` rule (studio.css:281, 351) and its `--seam-lip` is transparent.
 */
@Composable
fun TetherSeam(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val t = LocalTetherTokens.current
    val edge = t.line
    val lip = t.seamLip
    val size = if (vertical) modifier.fillMaxHeight().width(2.dp) else modifier.fillMaxWidth().height(2.dp)
    Canvas(size.clearAndSetSemantics { }) {
        val px = 1.dp.toPx()
        if (vertical) {
            // Lip on the inner (start) side of the border, like `inset -1px 0 0 var(--seam-lip)`.
            drawRect(lip, topLeft = Offset(0f, 0f), size = Size(px, this.size.height))
            drawRect(edge, topLeft = Offset(px, 0f), size = Size(px, this.size.height))
        } else {
            drawRect(edge, topLeft = Offset(0f, 0f), size = Size(this.size.width, px))
            drawRect(lip, topLeft = Offset(0f, px), size = Size(this.size.width, px))
        }
    }
}

/**
 * The machined families' micro-perforated divider under a dialog/sheet header (globals.css
 * 9255-9281): `--perf-dots` (`radial-gradient(circle at 1px 1px, <c> 1px, transparent 1px)`) tiled
 * 6px × 3px, 3px tall, opacity 0.55. The ABS families and Studio set `--perf-dots: none`: nothing.
 */
@Composable
fun PerfDivider(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val dot = perfDotColor(t.css.perfDots) ?: return
    Canvas(modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics { }) {
        val tile = 6.dp.toPx()
        val r = 1.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawCircle(dot.copy(alpha = dot.alpha * 0.55f), radius = r, center = Offset(x + r, r))
            x += tile
        }
    }
}

/** The dot colour of a `--perf-dots` value, or null for `none`. */
fun perfDotColor(css: String): Color? {
    val hex = Regex("#([0-9a-fA-F]{6})").find(css)?.groupValues?.get(1) ?: return null
    return Color(0xFF000000 or hex.toLong(16))
}
