package com.tether.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.ui.theme.LocalNavBarOverride
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

    private val navHolder = mutableStateOf<Color?>(null)
    private var panel: Color? = null
    private var rail: Color? = null

    private fun show() {
        rule.setContent {
            TetherTheme {
                rail = com.tether.app.ui.theme.LocalTetherTokens.current.css.bgRail
                panel = com.tether.app.ui.theme.LocalTetherTokens.current.css.bgPanel
                CompositionLocalProvider(LocalSystemBarOverride provides holder, LocalNavBarOverride provides navHolder) {
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
        assertEquals(rail, holder.value)
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        assertNull("closed again", holder.value)
    }

    @Test fun removingTheOpenHostClearsTheOverride() {
        open = true
        show()
        assertEquals(rail, holder.value)
        rule.runOnUiThread { hosted = false }
        rule.waitForIdle()
        assertNull("disposed", holder.value)
    }

    /** P8C-FIX3: the area below the footer takes the footer's panel colour while open; closing / removing restores the previous value exactly. */
    @Test fun theNavigationBarTakesTheFooterPanelWhileOpenAndRestoresThePreviousColour() {
        val previous = Color(0xFF123456)
        navHolder.value = previous
        show()
        assertEquals("closed: untouched", previous, navHolder.value)
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        assertEquals(panel, navHolder.value)
        rule.runOnUiThread { open = false }
        rule.waitForIdle()
        assertEquals("closed: the previous colour, exactly", previous, navHolder.value)
        rule.runOnUiThread { open = true }
        rule.waitForIdle()
        rule.runOnUiThread { hosted = false }
        rule.waitForIdle()
        assertEquals("host removed: restored", previous, navHolder.value)
    }
}
