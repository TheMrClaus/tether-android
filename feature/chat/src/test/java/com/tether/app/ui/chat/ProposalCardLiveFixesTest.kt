package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

private val liveView = ProposalView(
    proposalId = "p1", title = "Split the parser", prompt = "Do the work.", suggestions = JsObj.EMPTY, replaced = emptyList(),
    state = ProposalState.Pending, refusalCode = null, startedSessionId = null, startedSessionName = null,
)

private val wideHost = object : ProposalOptionsHost {
    @Composable override fun Rows(request: ProposalOptionsRequest) { Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray)) }
}

/** The Options panel's width is the card's, not the window's: on a tablet-size window and on a phone-size one. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-420dpi")
class ProposalOptionsPanelWidthTabletTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theTabletPanelIsNoWiderThanTheCard() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = wideHost, cardShown = true)) {
                    Box(Modifier.padding(start = 300.dp, end = 300.dp)) { ProposalCard(liveView) }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-options").performClick()
        rule.waitForIdle()
        val card = rule.onNodeWithTag("proposal-card").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("proposal-options-panel").getUnclippedBoundsInRoot()
        val cardWidth = card.right - card.left
        val panelWidth = panel.right - panel.left
        assertTrue("panel $panelWidth is wider than the card $cardWidth", panelWidth <= cardWidth)
        assertTrue("panel $panelWidth is not about the card width less its insets (card $cardWidth)", panelWidth >= cardWidth - 32.dp)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalOptionsPanelWidthPhoneTest {
    @get:Rule val rule = createComposeRule()

    @Test fun thePhonePanelIsNoWiderThanTheCard() {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", optionsHost = wideHost, cardShown = true)) {
                    Box(Modifier.padding(horizontal = 16.dp)) { ProposalCard(liveView) }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-options").performClick()
        rule.waitForIdle()
        val card = rule.onNodeWithTag("proposal-card").getUnclippedBoundsInRoot()
        val panel = rule.onNodeWithTag("proposal-options-panel").getUnclippedBoundsInRoot()
        assertTrue(panel.right - panel.left <= card.right - card.left)
    }
}

/** The preview Dialog's own window carries the theme's system-bar icon contrast. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProposalPreviewBarAppearanceTest {
    @get:Rule val rule = createComposeRule()

    private fun openPreviewIn(skin: TetherSkin) {
        rule.setContent {
            ChatHost(skin) { CompositionLocalProvider(LocalProposalActions provides ProposalActions(sessionId = "s1", cardShown = true)) { ProposalCard(liveView) } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("proposal-preview").performClick()
        rule.waitForIdle()
    }

    private fun appearance(): Pair<Boolean, Boolean> {
        val window = ShadowDialog.getLatestDialog()?.window
        assertNotNull("no preview dialog window", window)
        val c = WindowCompat.getInsetsController(window!!, window.decorView)
        return c.isAppearanceLightStatusBars to c.isAppearanceLightNavigationBars
    }

    @Test fun aLightThemeGivesTheDialogDarkBarIcons() {
        openPreviewIn(TetherSkin.Studio)
        assertEquals(true to true, appearance())
    }

    @Test fun aDarkThemeGivesTheDialogLightBarIcons() {
        openPreviewIn(TetherSkin.StudioDark)
        assertEquals(false to false, appearance())
    }
}
