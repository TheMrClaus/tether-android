package com.tether.app.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * T3.2 / T14.2: native all-caps labels keep the ORIGINAL words as the accessible name. The web sets
 * the label in sentence case and lets CSS `text-transform: uppercase` draw the capitals, so the DOM
 * text a screen reader gets is the original word ("Chat", not "CHAT"); Compose has no text-transform,
 * so the drawn string is upper-cased and this carries the original words. The drawn text stays in the
 * semantics tree too (test finders still match it); TalkBack prefers the description.
 *
 * Apply it to the `Text` that draws the capitals: `Text(label.uppercase(), modifier = Modifier.originalWords(label))`.
 */
fun Modifier.originalWords(words: String): Modifier = semantics { contentDescription = words }

/**
 * Swallows taps on a surface that sits over a tap-to-dismiss scrim, WITHOUT `clickable` (which merges
 * every descendant into one button node and so hides the keys and fields inside from TalkBack and from
 * test finders). A pointer-only consumer adds no semantics node of its own.
 */
fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
