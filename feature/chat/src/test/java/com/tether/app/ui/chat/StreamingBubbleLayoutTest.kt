package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-jtfq: a streaming agent message is laid out once per delta. The bubble used to be wrapped in an intrinsic-width
 * pass (shrink-to-fit), which laid the whole growing text out one more time per delta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class StreamingBubbleLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun textOf(n: Int) = "A streaming reply that keeps growing, delta number $n, with enough words to wrap onto a second line on a phone. ".repeat(n + 1)

    @Test
    fun aStreamingDeltaTakesNoIntrinsicPass() {
        var block by mutableStateOf(TurnBlock("t1:m0", "message", text = textOf(0), done = false))
        var intrinsicQueries = 0
        rule.setContent {
            ChatHost(TetherSkin.Studio) {
                CompositionLocalProvider(LocalStreamingTextObserver provides { intrinsicQueries++ }) { AgentBubble(block) }
            }
        }
        rule.waitForIdle()
        assertEquals("the first frame: no intrinsic pass", 0, intrinsicQueries)
        repeat(6) { n ->
            rule.runOnIdle { block = block.copy(text = textOf(n + 1)) }
            rule.waitForIdle()
            assertEquals("delta $n: the text is measured, never asked its intrinsic size", 0, intrinsicQueries)
        }
    }
}
