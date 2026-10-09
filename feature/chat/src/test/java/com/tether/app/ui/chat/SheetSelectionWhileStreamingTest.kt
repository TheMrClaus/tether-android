package com.tether.app.ui.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b (suspect 2): the activity sheet's words are selectable AND its running tool's output grows under the selection. A
 * selection (select all, then a handle drag) is held while many deltas arrive, then the call ends, then the block is replaced by
 * a shorter output; no step may throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [NoMagnifier::class])
class SheetSelectionWhileStreamingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private class MenuSpy : androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider {
        @Volatile var shown: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider? = null
        override suspend fun showTextContextMenu(dataProvider: androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider) {
            shown = dataProvider
            try { kotlinx.coroutines.awaitCancellation() } finally { if (shown === dataProvider) shown = null }
        }
        fun press(key: Any) {
            val menu = checkNotNull(shown) { "no text toolbar is open" }
            val item = menu.data().components.filterIsInstance<androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem>().first { it.key == key }
            item.onClick(object : androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession { override fun close() = Unit })
        }
    }

    private fun grow(from: ChatFixtures.Folded, chunk: String): ChatFixtures.Folded {
        val tree = foldTree(from.tree, ev("tool_output_delta", "t1", ts = ActivityFixtures.T) { put("toolId", "cmd-live"); put("chunk", chunk) })
        return ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test fun aSelectionHeldInTheSheetSurvivesTheOutputGrowingEndingAndBeingReplaced() {
        val menu = MenuSpy()
        var fixture by mutableStateOf(ActivityFixtures.runningCommand)
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider provides menu,
                LocalToolMediaLoader provides ToolFixtures.FakeLoader(),
            ) {
                ChatHost(TetherSkin.StudioDark) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone, showTimeline = false, richCodex = true,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.openRow("Shell npm test, running")
        val output = rule.onNode(hasText("ok 1 - backs off", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
        output.performTouchInput { longClick(center) }
        rule.waitUntil(20_000) { menu.shown != null }
        menu.press(androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys.SelectAllKey)
        rule.waitForIdle()
        // Drag from inside the selection while output arrives, longer and longer, one line and many lines.
        for (i in 1..60) {
            rule.runOnIdle { fixture = grow(fixture, if (i % 7 == 0) "line $i ${"wide ".repeat(300)}\n" else "ok $i\n") }
            rule.waitForIdle()
            if (i % 10 == 0) {
                rule.onNode(hasText("ok 1 - backs off", substring = true) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                    .performTouchInput { swipe(Offset(centerX, centerY), Offset(centerX, bottom - 1f), 80) }
                rule.waitForIdle()
            }
        }
        rule.runOnIdle { fixture = ActivityFixtures.finishCommand(fixture, more = "done\n") }
        rule.waitForIdle()
        // The same call, its output replaced by something far shorter, under the same open selection.
        val short = ActivityFixtures.finishCommand(ActivityFixtures.runningCommand, more = "")
        rule.runOnIdle { fixture = short }
        rule.waitForIdle()
        rule.runOnIdle { fixture = ChatFixtures.idle }
        rule.waitForIdle()
    }
}
