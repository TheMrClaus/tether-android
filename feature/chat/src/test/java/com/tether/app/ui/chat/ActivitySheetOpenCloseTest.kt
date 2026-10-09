package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.espresso.Espresso
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ta-a5jl A2: a tap on the row opens the sheet (titled by the verb), Close and Back close it, and it opens again. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivitySheetOpenCloseTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun dialogs() = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size

    private val shellTitle = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Shell")

    private fun open() {
        rule.showTranscript(ActivityFixtures.finishedShell, groupsOpen = true)
        rule.openRow("Shell git -C")
    }

    @Test fun aTapOpensTheSheetWithTheCommandAndItsOutput() {
        open()
        assertEquals(1, dialogs())
        rule.onNode(shellTitle, useUnmergedTree = true).assertExists()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Shell")).assertExists()
        // The whole command (it wraps; nothing clips it) and the output are in the sheet.
        rule.onNode(hasText(ActivityFixtures.LONG_COMMAND, substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("ta-a5jl: compact rows", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
    }

    @Test fun closeAndBackCloseItAndItOpensAgain() {
        open()
        assertEquals(1, dialogs())
        rule.onNode(hasContentDescription("Close") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(0, dialogs())
        rule.openRow("Shell git -C")
        assertEquals(1, dialogs())
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(0, dialogs())
    }
}
