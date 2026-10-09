package com.tether.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ta-jtfq: observes recompositions (test seam, like the transcript's row observer): called with the composable's name
 * every time it actually (re)composes. Null in production, where [RecompositionProbe] costs one local read.
 */
val LocalRecompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Put it in a composable's body to count that composable's own recompositions. Inline, so it is never skipped on its
 * own: it runs exactly when the function around it runs.
 */
@Composable
inline fun RecompositionProbe(name: String) {
    val probe = LocalRecompositionProbe.current
    if (probe != null) SideEffect { probe(name) }
}
