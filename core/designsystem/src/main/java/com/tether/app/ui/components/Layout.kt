package com.tether.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Constrain a child's MAX width to a fraction of the incoming max (CSS max-width: N%). */
fun Modifier.maxWidthFraction(fraction: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val cappedMax = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth * fraction).toInt()
        } else {
            constraints.maxWidth
        }
        val placeable = measurable.measure(
            constraints.copy(maxWidth = cappedMax, minWidth = minOf(constraints.minWidth, cappedMax)),
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    },
)

/**
 * `width(IntrinsicSize.Max)` that cannot throw: the content's widest line, up to the room it has.
 *
 * Compose's own modifier asks for a FIXED width equal to the content's max-intrinsic width, and a Constraints cannot hold a size
 * of 262,143 px or more ("Can't represent a width of N and height of 0 in Constraints"): one paragraph of some 19,000
 * characters on a single line (a pasted minified file, a long log line) made that a thrown exception in the layout pass, which
 * ends the process. The result is the same wherever the line fits (the widest line, enforced inside the incoming constraints);
 * a line wider than the incoming maximum is wrapped at it, as the incoming maximum would have made it anyway.
 */
fun Modifier.widthMaxContent(): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth else WIDTH_MAX_CONTENT_CAP
        // A child taller than a Constraints can hold (a Column asks its cross size at its own height) throws from inside the
        // intrinsic query too: such content has no meaningful widest line, so it takes the room it has.
        val wanted = try {
            measurable.maxIntrinsicWidth(constraints.maxHeight).coerceIn(0, room)
        } catch (_: IllegalArgumentException) {
            room
        }
        val width = wanted.coerceAtLeast(constraints.minWidth.coerceAtMost(room))
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    },
)

/** Below Compose's own limit (262,143 px) with room to spare for the height bits. */
private const val WIDTH_MAX_CONTENT_CAP = 131_072
