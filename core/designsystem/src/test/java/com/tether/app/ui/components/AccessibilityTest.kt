package com.tether.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** T14.2: the accessible names and the node structure a screen reader (and a semantics finder) gets. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun originalWordsKeepTheDrawnCapitalsAndNameTheOriginalWord() {
        rule.setContent {
            TetherTheme {
                Column {
                    Text("CHAT", modifier = Modifier.testTag("lone").originalWords("Chat"))
                    Column(Modifier.testTag("group").semantics(mergeDescendants = true) {}) {
                        Text("MODEL", modifier = Modifier.originalWords("Model"))
                        Text("claude-opus-5-5")
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Chat").assertExists()
        rule.onNodeWithText("CHAT").assertExists()
        // Inside a merged group the original word joins the group's description, beside the other text.
        rule.onNodeWithContentDescription("Model").assertExists()
        rule.onNodeWithText("claude-opus-5-5").assertExists()
    }

    @Test fun aDialogsKeysAndTextStayAsSeparateNodesAndItsTitleIsAHeadingAndPaneTitle() {
        var dismissed = 0
        rule.setContent {
            TetherTheme {
                TetherDialog(
                    onDismiss = { dismissed++ },
                    title = "Rename session",
                    footer = {
                        TetherKey(onClick = {}, label = "Cancel")
                        TetherKey(onClick = {}, label = "Rename")
                    },
                ) { TetherDialogText("Pick a new name.") }
            }
        }
        rule.onNodeWithContentDescription("Cancel").assertHasClickAction()
        rule.onNodeWithContentDescription("Rename").assertHasClickAction()
        // ta-5tb: the surface itself is not a button (it used to be `clickable`, merging every key into one node).
        rule.onAllNodes(hasClickAction()).assertCountEquals(2)
        rule.onNodeWithText("Rename session").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNode(SemanticsMatcher("pane title") { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Rename session" }).assertExists()

        // A tap on the dialog's own text is swallowed; a tap on the scrim dismisses.
        rule.onNodeWithText("Pick a new name.").performClick()
        assertEquals(0, dismissed)
        rule.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(1, dismissed)
    }

    @Test fun aSheetsRowsAndTitleStayAsSeparateNodesAndItsTitleIsAHeadingAndPaneTitle() {
        var dismissed = 0
        rule.setContent {
            TetherTheme {
                TetherSheet(onDismiss = { dismissed++ }, title = "Attach") {
                    TetherSheetRow("Photos", onClick = {})
                    TetherSheetRow("Files", onClick = {})
                }
            }
        }
        rule.onNodeWithContentDescription("Photos").assertHasClickAction()
        rule.onNodeWithContentDescription("Files").assertHasClickAction()
        // ta-6gw: two rows and the Close key; the surface is not a clickable that merges them into one.
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)
        rule.onNodeWithText("Attach").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNode(SemanticsMatcher("pane title") { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Attach" }).assertExists()

        rule.onNodeWithText("Attach").performClick()
        assertEquals(0, dismissed)
        rule.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(1, dismissed)
    }

    @Test fun theWordmarkIsNamedTether() {
        rule.setContent { TetherTheme { Wordmark() } }
        rule.onNodeWithContentDescription("Tether").assertExists()
    }
}
