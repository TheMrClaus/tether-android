package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-09ca E3: the Codex plan card's status label drops under the step at `(max-width: 42rem)`
 * (codex-rich-renderers.module.css:397), which is 672 dp, not at the shell's 768: at 672 the label is under the step,
 * at 673 it sits beside it. At the shell's own edge (768) it is beside the step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w672dp-h900dp-mdpi")
class CodexPlanCardEdgeTest {
    @get:Rule val rule = createComposeRule()

    private val plan = PlanView("Ship it", listOf(PlanStep("Write the migration", "in_progress")))

    private fun host() {
        rule.setContent { TetherTheme { Box(Modifier.fillMaxWidth()) { CodexPlanCard(plan) } } }
        rule.waitForIdle()
    }

    private fun assertUnder() {
        host()
        val step = rule.onNodeWithText("Write the migration").fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText("IN PROGRESS").fetchSemanticsNode().boundsInRoot
        assertTrue("672: the label is under the step: $label vs $step", label.top >= step.bottom - 1f && label.left < step.right)
    }

    private fun assertBeside() {
        host()
        val step = rule.onNodeWithText("Write the migration").fetchSemanticsNode().boundsInRoot
        val label = rule.onNodeWithText("IN PROGRESS").fetchSemanticsNode().boundsInRoot
        assertTrue("beside: the label is right of the step: $label vs $step", label.left >= step.right - 1f && label.top < step.bottom)
    }

    @Test fun at672TheStatusDropsUnderTheStep() = assertUnder()

    @Test @Config(qualifiers = "w673dp-h900dp-mdpi")
    fun at673TheStatusSitsBesideTheStep() = assertBeside()

    @Test @Config(qualifiers = "w768dp-h900dp-mdpi")
    fun atTheShellsOwnEdgeTheStatusIsBesideTheStep() = assertBeside()
}
