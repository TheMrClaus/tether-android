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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tether.app.ui.theme.LocalTetherTokens

/**
 * A parting line between two molded parts (globals.css 9013-9019, 10850-10874): Studio's plain
 * `1px var(--line)` rule (studio.css:281, 351) in a 2px band. The second pixel held the lit
 * `--seam-lip` (transparent in both Studio skins, retired at tether 887c222); it stays empty so
 * the seam keeps its footprint. Vertical seams (rail / inspector) keep the empty pixel on the
 * inner (start) side.
 */
@Composable
fun TetherSeam(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val t = LocalTetherTokens.current
    val edge = t.line
    val size = if (vertical) modifier.fillMaxHeight().width(2.dp) else modifier.fillMaxWidth().height(2.dp)
    Canvas(size.clearAndSetSemantics { }) {
        val px = 1.dp.toPx()
        if (vertical) {
            drawRect(edge, topLeft = Offset(px, 0f), size = Size(px, this.size.height))
        } else {
            drawRect(edge, topLeft = Offset(0f, 0f), size = Size(this.size.width, px))
        }
    }
}
