package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.ERROR_TOAST_TAG
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.7 r2 through MainShell: the error toast is drawn over the composer's keys. A tap on the toast
 * never reaches the Interrupt key under it; and when the toast goes away every armed key re-arms, so
 * a tap aimed at the vanishing toast does not land on the key it uncovered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
class MainShellToastTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(412, 915)
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "runs", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Keep going.") },
    )

    private lateinit var vm: TetherViewModel

    /** The composer's Interrupt key (feature/chat INTERRUPT_KEY_TAG). */
    private val interruptKey = "composer-interrupt"

    private fun host(client: ShellConsentClient) {
        vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        arm()
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
    }

    private fun tapRootAt(point: Offset) {
        rule.onRoot().performTouchInput { click(point) }
        rule.mainClock.advanceTimeBy(48)
        rule.waitForIdle()
    }

    @Test
    fun aTapOnTheToastOverAnArmedKeySendsNothing() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        val key = rule.onNodeWithTag(interruptKey).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { vm.reportLocalError("Not connected — the setting was not changed.") }
        arm()
        val toast = rule.onNodeWithTag(ERROR_TOAST_TAG).fetchSemanticsNode().boundsInRoot
        // On a phone the toast's X sits right over the Interrupt key.
        val under = key.intersect(toast)
        assertTrue("precondition: the toast covers the Interrupt key (key $key, toast $toast)", under.width > 0f && under.height > 0f)

        tapRootAt(under.center)
        assertTrue("a tap on the toast reached the key under it: ${client.interruptCalls}", client.interruptCalls.isEmpty())
        rule.onNodeWithTag(ERROR_TOAST_TAG).assertDoesNotExist() // the tap was the X's: the toast closed

        // The toast went away: the key it uncovered re-arms before a second tap can land on it.
        tapRootAt(under.center)
        assertTrue("a tap aimed at the vanishing toast interrupted: ${client.interruptCalls}", client.interruptCalls.isEmpty())

        arm()
        rule.onNodeWithTag(interruptKey).performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN#t1"), client.interruptCalls)
    }
}
