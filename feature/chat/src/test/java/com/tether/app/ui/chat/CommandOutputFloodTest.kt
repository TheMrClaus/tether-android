package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.reduce.tree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.4 round 2 (L4): a command streaming a flood of chunks into an open, nearly full output sheet
 * costs one rebuild of the drawn tail per [COMMAND_SHEET_SAMPLE_MS] (about 4 Hz), off the main
 * thread, not one 64k-character rebuild per chunk; the sheet still ends on the newest output, and
 * the tail stays within [COMMAND_SHEET_MAX_CHARS].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CommandOutputFloodTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun output(tree: JsObj, text: String, stream: String = "stdout"): JsObj =
        reduce(tree, evNullTurn("background_command_output", ts = 2) { put("commandId", "c"); put("stream", stream); put("text", text) }.tree())

    @Test fun aFloodOfChunksRebuildsTheSheetAboutFourTimesASecond() {
        var tree = reduce(freshTree(), evNullTurn("background_command_updated", ts = 1) {
            put("commandId", "c"); put("command", "yes"); put("cwd", "/w"); put("logFile", "/w/c.log"); put("status", "running"); put("startedAt", 1)
        }.tree())
        // Nearly full: 60,000 characters of 60-character lines already captured.
        tree = output(tree, ("x".repeat(59) + "\n").repeat(1_000))
        var state by mutableStateOf(tree)
        val rebuilds = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalChatRowObserver provides { key: String -> if (key == "command-output-lines") rebuilds += key }) {
                    CommandOutputSurface(runningBackgroundCommands(state).single(), CommandActions.Unavailable, onClose = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()
        rebuilds.clear()
        // 200 chunks, one per 16 ms frame: 3.2 s of virtual time.
        val chunks = 200
        repeat(chunks) { i ->
            rule.runOnIdle { state = output(state, "chunk $i ${"y".repeat(200)}\n", if (i % 5 == 0) "stderr" else "stdout") }
            rule.mainClock.advanceTimeBy(16)
        }
        // The rebuild runs on a background dispatcher: let it land and the list follow the tail.
        repeat(10) {
            rule.mainClock.advanceTimeBy(COMMAND_SHEET_SAMPLE_MS / 2)
            Thread.sleep(20)
            rule.waitForIdle()
        }
        val allowed = ((chunks * 16 + 10 * COMMAND_SHEET_SAMPLE_MS / 2) / COMMAND_SHEET_SAMPLE_MS).toInt() + 3
        assertTrue("rebuilt ${rebuilds.size} times for $chunks chunks (allowed $allowed)", rebuilds.size in 1..allowed)
        rule.onNodeWithText("chunk ${chunks - 1}", substring = true, useUnmergedTree = true).assertIsDisplayed()
        val tail = outputTail(outputSegments(runningBackgroundCommands(state).single().segments))
        assertTrue(tail.segments.sumOf { it.text.length } <= COMMAND_SHEET_MAX_CHARS)
        assertTrue("the oldest output is dropped from the drawn tail", tail.dropped > 0)
    }
}
