package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-cqf (T6.2 follow-up, folded into T6.4): a sub-agent thread under its parent card draws 50
 * steps at a time, and its "+N more steps" / "+N earlier steps" row is a 44dp key that draws the
 * next 50; a finished thread opens by default when ANY of its steps' results carries media (the
 * web checks every step), not only the 50 drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SubagentThreadPagingTest {
    @get:Rule val rule = createComposeRule()

    private fun entry(n: Int, media: Boolean = false): Pair<String, JsObj> {
        val output = if (media) {
            JsArr.of(JsObj.of("type" to js("media_ref"), "mediaKind" to js("image"), "mediaType" to js("image/png"), "url" to js(ToolFixtures.CHART_URL)))
        } else {
            js("ok $n")
        }
        return "e$n" to JsObj.of("key" to js("e$n"), "kind" to js("tool"), "name" to js("Read"), "input" to JsObj.of("file_path" to js("f$n")), "output" to output, "done" to JsBool.TRUE)
    }

    private fun agent(steps: Int, done: Boolean, mediaAt: Int? = null): JsObj {
        val entries = (1..steps).map { entry(it, media = it == mediaAt) }
        val thread = JsObj.of("order" to JsArr.of(entries.map { JsStr(it.first) }), "entries" to JsObj.from(entries.toMap()))
        return JsObj.of(
            "blockId" to js("task-1"), "kind" to js("tool"), "name" to js("Agent"), "input" to JsObj.of("description" to js("Read all")),
            "subagent" to thread, "done" to JsBool.of(done), "output" to js("finished"),
        )
    }

    private fun show(block: JsObj) {
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ToolCard(block, showThinking = false)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun theWindowReportsMediaFromAnyStep() {
        val thread = agent(120, done = true, mediaAt = 90)["subagent"] as JsObj
        val window = subagentWindow(thread, showThinking = false, newest = false)
        assertEquals(50, window.entries.size)
        assertEquals(70, window.later)
        assertTrue("step 90's picture counts although only 50 are drawn", window.hasMedia)
        assertFalse(subagentWindow(agent(120, done = true)["subagent"] as JsObj, showThinking = false, newest = false).hasMedia)
        assertEquals(100, subagentWindow(thread, showThinking = false, newest = false, max = 100).entries.size)
    }

    @Test fun moreStepsPagesTheNextFiftyUntilAllAreDrawn() {
        show(agent(120, done = true, mediaAt = 110))
        // Finished, with a picture at step 110 (beyond the first 50): open by default.
        val more = rule.onNodeWithTag("steps-more")
        more.performScrollTo().assertHeightIsAtLeast(44.dp)
        rule.onNodeWithText("+70 more steps").assertExists()
        more.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+20 more steps").assertExists()
        rule.onNodeWithTag("steps-more").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("steps-more").assertCountEquals(0)
    }

    @Test fun aRunningThreadPagesItsEarlierSteps() {
        show(agent(75, done = false))
        rule.onNodeWithText("+25 earlier steps").assertExists()
        rule.onNodeWithTag("steps-more")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
            .performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("steps-more").assertCountEquals(0)
    }
}

