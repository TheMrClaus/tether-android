package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.LocalSystemBarOverride
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-1jj7 (owner-directed design): the open drawer fills the window, so the system bars take its colour
 * (no white bands around a dark panel); closing it, or removing the host, hands them back to the skin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerBarOverrideTest {
    @get:Rule val rule = createComposeRule()

    private val holder = mutableStateOf<Color?>(null)
    private var open by mutableStateOf(false)
    private var hosted by mutableStateOf(true)

    private fun show() {
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalSystemBarOverride provides holder) {
                    if (hosted) SessionDrawerHost(open = open) { Box {} }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun theBarsTakeTheOpenDrawersColourAndLeaveWithIt() {
        show()
        assertNull("closed: the skin's own", holder.value)
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        assertEquals(StudioDrawer.background, holder.value)
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        assertNull("closed again", holder.value)
    }

    @Test fun removingTheOpenHostClearsTheOverride() {
        open = true
        show()
        assertEquals(StudioDrawer.background, holder.value)
        rule.runOnUiThread { hosted = false }
        rule.waitForIdle()
        assertNull("disposed", holder.value)
    }
}
