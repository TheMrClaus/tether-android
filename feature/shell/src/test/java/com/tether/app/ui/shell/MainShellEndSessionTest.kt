package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
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
 * T13.2 r2 through MainShell: End session needs a live link AND a live copy of the session, at the
 * header key and again at the confirmation (a link that drops under the open dialog disables it).
 * The kill asks the client to re-check the live set (requireLive).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h1000dp-mdpi")
class MainShellEndSessionTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(600, 1000)
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "ends", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val tree = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Keep going.") },
    )

    private fun confirmKey() = rule.onAllNodesWithText("End session").filterToOne(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    private fun host(client: ShellConsentClient) {
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        rule.waitForIdle()
    }

    @Test
    fun theConfirmationStopsActingWhenTheCopyStopsBeingLive() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled()

        // The link drops under the open dialog: the saved copy cannot end the session.
        rule.runOnIdle {
            client.link.value = ConnectionState.Disconnected
            client.live.value = emptySet()
        }
        rule.waitForIdle()
        confirmKey().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("nothing from a saved copy: ${client.killCalls}", client.killCalls.isEmpty())
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsNotEnabled()

        // Back, but not yet confirmed on the new link (catching up): still disabled.
        rule.runOnIdle { client.link.value = ConnectionState.Connected }
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsNotEnabled()

        rule.runOnIdle { client.live.value = setOf("s1") }
        rule.waitForIdle()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$SHELL_TEST_ORIGIN:true"), client.killCalls)
    }

    /**
     * r3: the confirmation carries the server it was opened for. A switch to another server under
     * the open dialog, even one where a same-id session is live, disables it and ends nothing.
     */
    @Test
    fun theConfirmationIsBoundToTheServerItWasOpenedFor() {
        val client = ShellConsentClient().also { it.show(session, tree) }
        host(client)
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled()

        rule.runOnIdle { client.origin.value = OTHER_ORIGIN }
        rule.waitForIdle()
        confirmKey().assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertTrue("an End opened for one server ended a session on another: ${client.killCalls}", client.killCalls.isEmpty())

        // Opened afresh on the current server: it ends there, bound to that origin.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ShellTags.EndSessionKey).assertIsEnabled().performClick()
        rule.waitForIdle()
        confirmKey().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1@$OTHER_ORIGIN:true"), client.killCalls)
    }

    private companion object {
        const val OTHER_ORIGIN = "https://other.example"
    }
}
