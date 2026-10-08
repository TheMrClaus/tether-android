package com.tether.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-v8dt (W21): the tab strip's inline margin follows globals.css 11014, `(max-width: 47.9375rem)`: 12 dp below 768 and
 * 24 dp from it, independent of the 640 switch (the tabs' padding stays on that one). The web at tether 29537e0: 12 at
 * 412 / 640 / 641 / 767, 24 at 800.
 */
abstract class SettingsTabsInsetBase(private val expected: Float) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun theTabStripSitsThisFarInFromTheCardsEdge() {
        compose.setContent {
            TetherTheme(TetherSkin.StudioDark.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsFrame(
                            prefs = store.prefs, serverUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null), state = SettingsDialogState(),
                            restartRequired = false, currentWorkspace = CURRENT, onClose = {}, layout = settingsLayout(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val card = compose.onNodeWithTag(SettingsDialogTags.Dialog).fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag(SettingsDialogTags.Tabs).fetchSemanticsNode().boundsInRoot
        assertEquals("tab strip inset", expected, tabs.left - card.left, 0.5f)
        assertEquals("and from the right edge", expected, card.right - tabs.right, 0.5f)
        println("W21-RECORD tabs inset=${tabs.left - card.left}")
    }
}

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w640dp-h900dp-mdpi") class SettingsTabsInset640Test : SettingsTabsInsetBase(12f)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w641dp-h900dp-mdpi") class SettingsTabsInset641Test : SettingsTabsInsetBase(12f)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w767dp-h900dp-mdpi") class SettingsTabsInset767Test : SettingsTabsInsetBase(12f)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w768dp-h900dp-mdpi") class SettingsTabsInset768Test : SettingsTabsInsetBase(24f)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w412dp-h915dp-mdpi") class SettingsTabsInset412Test : SettingsTabsInsetBase(12f)
