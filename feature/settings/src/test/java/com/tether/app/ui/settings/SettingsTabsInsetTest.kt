package com.tether.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
abstract class SettingsTabsInsetBase(private val expanded: Boolean) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    @Test fun runtimeDeskSwitchesAtTheContractBreakpoint() {
        compose.setContent {
            TetherTheme(TetherSkin.StudioDark.mode) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize()) {
                        SettingsFrame(
                            prefs = store.prefs, serverUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null),
                            state = SettingsDialogState(route = RuntimeDeskRoute.Root),
                            restartRequired = false, currentWorkspace = CURRENT, onClose = {}, layout = settingsLayout(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SettingsDialogTags.Dialog).assertExists()
        compose.onNode(
            hasText("Preferences") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        ).assertExists()
        if (expanded) compose.onNodeWithText("Startup").assertExists() else compose.onNodeWithText("Startup").assertDoesNotExist()
    }
}

@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w640dp-h900dp-mdpi") class SettingsTabsInset640Test : SettingsTabsInsetBase(false)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w641dp-h900dp-mdpi") class SettingsTabsInset641Test : SettingsTabsInsetBase(false)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w767dp-h900dp-mdpi") class SettingsTabsInset767Test : SettingsTabsInsetBase(false)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w768dp-h900dp-mdpi") class SettingsTabsInset768Test : SettingsTabsInsetBase(true)
@RunWith(RobolectricTestRunner::class) @Config(qualifiers = "w412dp-h915dp-mdpi") class SettingsTabsInset412Test : SettingsTabsInsetBase(false)
