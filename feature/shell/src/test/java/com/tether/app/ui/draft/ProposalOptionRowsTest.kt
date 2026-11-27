package com.tether.app.ui.draft

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import com.tether.app.ui.chat.ProposalReplacedView
import com.tether.app.ui.shell.choiceFor
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v175: a replaced row's line names the value the row shows (its display label), never a raw id. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalOptionRowsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theReplacedLineNamesTheModelAsTheRowShowsIt() {
        val seed = ProposalRowsSeed(ProposalOptionsShot.Replaced)
        val replaced = listOf(ProposalReplacedView("model", "invalid_settings", "opus-9"))
        rule.setContent { TetherTheme(choiceFor(TetherSkin.Studio)) { ProposalOptionRowsFrame(seed.inputs(ProposalOptionsShot.Replaced, replaced), ProposalRowsActions()) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ProposalOptionTags.Replaced).assertTextContains("Suggested model “opus-9” is not available here; using “Model 2”.")
        seed.close()
    }
}
