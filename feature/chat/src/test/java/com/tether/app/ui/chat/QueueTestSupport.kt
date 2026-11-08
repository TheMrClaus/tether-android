package com.tether.app.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/**
 * tth-5u90 / D-GH-8: a queued row's full wait label is the open area's `stateDescription` (the one polite live region), no longer
 * a visible line, so the behaviour tests read it here. The label's words are unchanged from the pre-D-GH-8 `Text` line.
 */
fun hasWait(label: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label)

/** The open area of the nth queued row (its merged, clickable, "Open message" node). */
fun ComposeTestRule.openArea(index: Int = 0): SemanticsNodeInteraction =
    onAllNodes(hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))[index]

/** Tap row [index] open (its sheet is a dialog). */
fun ComposeTestRule.openRowSheet(index: Int = 0) {
    openArea(index).performClick()
    waitForIdle()
}

fun hasWaitContaining(part: String): SemanticsMatcher = SemanticsMatcher("StateDescription contains '$part'") {
    it.config.getOrNull(SemanticsProperties.StateDescription)?.contains(part) == true
}
