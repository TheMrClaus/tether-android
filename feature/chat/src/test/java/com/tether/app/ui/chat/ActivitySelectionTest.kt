package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.theme.TetherSkin
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import com.tether.app.protocol.model.TurnBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl A13: the rows are single controls, not reading (a tool / thinking Block is not selectable, a message is), and the
 * words are selectable where they now live: in the sheet, which has its own selection container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class ActivitySelectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun block(kind: String) = ChatItem.Block("t1", TurnBlock("b", kind, text = "x"), "", startsGroup = true)

    @Test fun aMessageIsSelectableAndTheRowsAreNot() {
        assertTrue(block("message").selectableText)
        assertTrue(block("user_message").selectableText)
        assertFalse(block("tool").selectableText)
        assertFalse(block("thinking").selectableText)
        assertTrue(block("tool").isActivityRow)
        assertTrue(block("thinking").isActivityRow)
        assertFalse(block("message").isActivityRow)
    }

    /** The text toolbar, spied: the test presses "Select all" and "Copy" as the operator would. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    private fun clip(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test fun theSheetsWordsAreSelectableAndCopyTheExactSource() {
        val menu = MenuSpy()
        val fixture = ActivityFixtures.finishedShell
        val toggles = allGroupsOpen(fixture)
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides menu,
            ) {
                ChatHost(TetherSkin.StudioDark) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone, showTimeline = false, groupToggles = toggles,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.openRow("Shell git -C")
        val output = rule.onNode(hasText("ta-a5jl: compact rows", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        output.assertExists()
        output.performTouchInput { longClick(center) }
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.CopyKey)
        rule.waitForIdle()
        val copied = checkNotNull(clip()) { "nothing was copied" }
        assertTrue("the output's exact line is copied: $copied", copied.contains("d4c3b2a (HEAD -> main) ta-a5jl: compact rows"))
        assertTrue("the command is copied whole: $copied", copied.contains(ActivityFixtures.LONG_COMMAND))
        assertFalse("the selection stays in the sheet: $copied", copied.contains("# README"))
    }
}
