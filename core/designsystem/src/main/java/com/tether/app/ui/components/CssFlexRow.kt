package com.tether.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How a [CssFlexRow] spends the free space once every item has its basis (`justify-content`). */
enum class FlexJustify { Start, SpaceBetween }

/**
 * A single-line CSS flex row of `flex: 0 1 auto; min-width: auto` items (ta-z4c1): the status line
 * (`.status-line`, globals.css 1003) and the sidebar footer (`.sidebar-footer`, globals.css 1065).
 *
 * Compose's `Row` measures its children in turn, each on whatever width is left, so a late child can
 * be measured narrower than its longest word and Android then splits the word. CSS Flexbox does not:
 * - the basis of an item is its max-content width, and its floor its min-content width (a Text's
 *   longest unbreakable segment; [flexFloor] overrides it);
 * - when everything fits, every item has its basis: the result is `Row(spacedBy(gap))`;
 * - otherwise the deficit is taken from the items in proportion to their basis, an item that hits
 *   its floor is frozen there, and the rest shrink on (CSS Flexbox section 9.7, the shrink half);
 * - each item is then measured at its target width, so a Text wraps at its spaces only;
 * - when the floors alone do not fit, the items sit at their floors and overflow the end. The row
 *   does not clip: the caller's clip (the phone row's `.is-mobile` `overflow: hidden`) does.
 */
@Composable
fun CssFlexRow(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    justify: FlexJustify = FlexJustify.Start,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable () -> Unit,
) {
    val policy = remember(gap, justify, verticalAlignment) { CssFlexRowPolicy(gap, justify, verticalAlignment) }
    Layout(content, modifier, policy)
}

/** An item's `min-width` (its floor) when it is not its min-content width: a dot floors at 0. */
fun Modifier.flexFloor(floor: Dp): Modifier = this.then(FlexFloorModifier(floor))

private class FlexFloorModifier(val floor: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = FlexFloor(floor)
}

private class FlexFloor(val floor: Dp)

/**
 * The target widths of items with [bases] and [floors] in [available] pixels (gaps already taken):
 * the bases when they fit, else the CSS flex-shrink resolution. Pure; the floors never exceed it.
 */
internal fun flexTargets(bases: IntArray, floors: IntArray, available: Float): FloatArray {
    val target = FloatArray(bases.size) { bases[it].toFloat() }
    if (bases.sum() <= available) return target
    val frozen = BooleanArray(bases.size)
    // An item whose floor is at or above its basis cannot shrink: frozen at its basis from the start.
    for (i in bases.indices) if (floors[i] >= bases[i]) frozen[i] = true
    while (true) {
        val open = bases.indices.filter { !frozen[it] }
        if (open.isEmpty()) break
        val scaled = open.sumOf { bases[it].toDouble() }
        val remaining = available - bases.indices.sumOf { (if (frozen[it]) target[it] else bases[it].toFloat()).toDouble() }
        if (scaled <= 0.0) break
        var violation = 0.0
        for (i in open) {
            val raw = (bases[i] + remaining * bases[i] / scaled).toFloat()
            target[i] = maxOf(raw, floors[i].toFloat())
            violation += target[i] - raw
        }
        if (violation == 0.0) break
        // Freeze the items that hit their floor (positive total violation) and shrink the rest again.
        for (i in open) if (target[i] <= floors[i].toFloat()) frozen[i] = true
    }
    return target
}

/**
 * The pixel widths of [targets]: each floored, then the pixels lost to flooring (up to the rounded
 * sum) go to the items with the largest remainders, so a row that fills its width still fills it
 * and `space-between` finds no stray pixel to spend (a Row + Spacer filled the leftover exactly).
 */
internal fun snapTargets(targets: FloatArray): IntArray {
    val ints = IntArray(targets.size) { targets[it].toInt() }
    var lost = Math.round(targets.sum()) - ints.sum()
    targets.indices.filter { targets[it] > ints[it] }.sortedByDescending { targets[it] - ints[it] }.forEach {
        if (lost > 0) { ints[it]++; lost-- }
    }
    return ints
}

private class CssFlexRowPolicy(
    private val gap: Dp,
    private val justify: FlexJustify,
    private val vertical: Alignment.Vertical,
) : MeasurePolicy {
    private fun IntrinsicMeasurable.floorPx(density: Density, height: Int, basis: Int): Int {
        val declared = (parentData as? FlexFloor)?.floor
        val raw = if (declared != null) with(density) { declared.roundToPx() } else minIntrinsicWidth(height)
        return raw.coerceIn(0, basis)
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val n = measurables.size
        val gapPx = gap.roundToPx()
        val gaps = gapPx * (n - 1).coerceAtLeast(0)
        val bases = IntArray(n) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) }
        val floors = IntArray(n) { measurables[it].floorPx(this, Constraints.Infinity, bases[it]) }
        val targets = if (constraints.hasBoundedWidth) {
            flexTargets(bases, floors, (constraints.maxWidth - gaps).toFloat().coerceAtLeast(0f))
        } else {
            FloatArray(n) { bases[it].toFloat() }
        }
        val snapped = snapTargets(targets)
        val placeables = measurables.mapIndexed { i, m ->
            // The basis is the max-content width; a shrunk item is measured at its target, snapped to pixels.
            val w = if (targets[i] >= bases[i]) bases[i] else snapped[i].coerceAtLeast(floors[i])
            m.measure(Constraints(maxWidth = w, maxHeight = constraints.maxHeight))
        }
        val contentWidth = placeables.sumOf { it.width } + gaps
        val width = constraints.constrainWidth(contentWidth)
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        return layout(width, height) {
            val extra = if (justify == FlexJustify.SpaceBetween && n > 1) ((width - contentWidth) / (n - 1)).coerceAtLeast(0) else 0
            var x = 0
            placeables.forEach { p ->
                p.place(x, vertical.align(p.height, height))
                x += p.width + gapPx + extra
            }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.maxIntrinsicWidth(height) } + gap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

    /**
     * The flex container's min-content width: each item's OWN min-content contribution plus the gaps. A declared
     * [flexFloor] is only the shrink floor inside this container's own layout (measure), never what it asks of its
     * parent: CSS counts the dot at its specified 0.4rem here, which keeps it from being shrunk to nothing by the
     * outer row (ta-z4c1 L3: the footer's dot vanished at 2.0x).
     */
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.sumOf { it.minIntrinsicWidth(height) } + gap.roundToPx() * (measurables.size - 1).coerceAtLeast(0)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicHeight(Constraints.Infinity) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicHeight(Constraints.Infinity) } ?: 0
}
