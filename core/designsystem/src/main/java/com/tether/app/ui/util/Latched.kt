package com.tether.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class Latch<T>(var held: T)

/**
 * ta-jtfq: keeps the reading it holds while [sameEnough] says the new one adds nothing for the reader of this value,
 * so a composable that takes the held value is skipped instead of recomposing for it. The held value is replaced the
 * moment [sameEnough] answers false (it is always compared against the held one, not the previous one), and when
 * [keys] change. It is not state and starts the held value out as [value].
 */
@Composable
fun <T> rememberLatched(value: T, vararg keys: Any?, sameEnough: (held: T, next: T) -> Boolean): T {
    val latch = remember(*keys) { Latch(value) }
    if (latch.held !== value && !sameEnough(latch.held, value)) latch.held = value
    return latch.held
}
