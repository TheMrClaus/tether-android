package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b: a message whose paragraph is one very long line (a pasted minified file, a long log line, a long paragraph with no
 * hard newline) made `width(IntrinsicSize.Max)` ask for a fixed width over what a Constraints can hold (262,143 px), and the
 * layout threw "Can't represent a width of N and height of 0 in Constraints": the process died. The bubble now takes the
 * widest line only up to the room it has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HugeLineBubbleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(prompt: String, reply: String) = show(ChatFixtures.fold(*ChatFixtures.turn("t1", prompt, reply, ChatFixtures.T_IDLE)))

    private fun show(fixture: ChatFixtures.Folded) {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 2000.dp) {
                ChatTranscript(
                    projection = fixture.projection, tree = fixture.tree, showThinking = false,
                    onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, showTimeline = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(WellTag).assertExists()
    }

    @Test fun anAgentParagraphOfOneHugeLineLaysOut() {
        show("go", "x ".repeat(30_000))
    }

    @Test fun aUserMessageOfOneHugeLineLaysOut() {
        show("y ".repeat(30_000), "ok")
    }

    private fun commandRun(command: String, output: String) = ChatFixtures.fold(
        ev("turn_started", "t1", ts = 1L) {
            put("idempotencyKey", "k-t1")
            put("commandRun", buildJsonObject { put("command", command); put("cwd", "/w"); put("logFile", "/w/.tether/commands/c-1.log") })
        },
        ev("command_output_started", "t1", ts = 1L) { put("blockId", "t1:cmd"); put("command", command); put("logFile", "/w/.tether/commands/c-1.log") },
        ev("command_output_delta", "t1", ts = 2L) { put("blockId", "t1:cmd"); put("stream", "stdout"); put("text", output) },
    )

    @Test fun aCommandPanelWithOneHugeOutputLineLaysOut() {
        show(commandRun("make", "o ".repeat(30_000)))
        rule.onNodeWithTag(COMMAND_PANEL_TAG).assertExists()
    }

    @Test fun aCommandPanelWithAHugeCommandLaysOut() {
        show(commandRun("c ".repeat(30_000), "ok\n"))
        rule.onNodeWithTag(COMMAND_PANEL_TAG).assertExists()
    }
}
