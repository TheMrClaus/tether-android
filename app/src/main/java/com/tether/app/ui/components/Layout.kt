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
