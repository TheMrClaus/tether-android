package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.3 round 4 (H1) through MainShell: the attention cards' store lives above the phone / expanded
 * switch, so a window crossing 768dp (a rotation, a foldable, a resize) keeps what the operator
 * unticked, and a grant still needs the unsaved confirmation made on the card now on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h1000dp-mdpi")
class MainShellCardStateTest {
    @get:Rule val rule = createComposeRule()

    private var widthDp by mutableIntStateOf(600)

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(widthDp, 1000) // mdpi: 1px = 1dp
    }

    private val session = AgentSession(id = "s1", provider = "claude", name = "grants", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)

    private val grants = foldTree(
        freshTree(),
        ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
        ev("user_message_accepted", "t1", ts = 1) { put("text", "Run the migration.") },
        ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-g"); put("toolId", "perm-1"); put("name", "permissions")
            putJsonArray("choices") {
                addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
            }
            putJsonObject("metadata") {
                put("provider", "codex"); put("kind", "permissions")
                putJsonObject("requestedPermissions") {
                    putJsonObject("fileSystem") {
                        putJsonArray("read") { add("/srv/fixtures"); add("/srv/schema.sql") }
                        putJsonArray("write") { add("/w/report") }
                    }
                    putJsonObject("network") { put("enabled", true) }
                }
            }
        },
    )

    private fun arm() {
        rule.mainClock.advanceTimeBy(600) // settle (ta-coik.13: the cards have no arm delay)
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    @Test fun aNarrowedGrantSurvivesTheWindowCrossingTheExpandedCutoff() {
        val client = ShellConsentClient()
        client.show(session, grants)
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) }
            }
        }
        rule.waitForIdle()
        assertEquals(TetherLayoutClass.Phone, shellLayoutFor(widthDp))
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick() // network off, in the phone layout
        rule.onNodeWithTag("grant-network").assertIsOff()

        // The window grows past 768dp: MainShell swaps PhoneShell for ExpandedShell.
        rule.runOnIdle { widthDp = 900 }
        rule.waitForIdle()
        assertEquals(TetherLayoutClass.Expanded, shellLayoutFor(widthDp))
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertIsOff() // what the operator unticked is still unticked
        rule.onAllNodesWithTag("grant-read")[0].performClick() // and they narrow further
        rule.onAllNodesWithTag("grant-read")[0].assertIsOff()
        rule.onAllNodesWithTag("grant-read")[1].assertIsOn()

        // chat-view.tsx 90fbb9f :1191-1205 (ta-coik.5): "subset" grants the ticked paths with no confirmation.
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("approval:s1:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = listOf("/w/report")).toJsonObject()),
            client.consentCalls,
        )
    }

    @Test fun aLayoutSwitchClearsTheConfirmationButNotTheTicks() {
        val client = ShellConsentClient()
        client.show(session, grants)
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) }
            }
        }
        rule.waitForIdle()
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        rule.runOnIdle { widthDp = 900 }
        rule.waitForIdle()
        arm()
        scrollTo("grant-confirm")
        // A new card instance: the "exact" confirmation was made on the old one and is not carried over.
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        rule.onNodeWithTag("grant-network").assertIsOff()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsNotEnabled()
        assertTrue(client.consentCalls.isEmpty())
    }

    @Test fun signingInToAnotherServerForgetsTheCardsButADropDoesNot() {
        val client = ShellConsentClient()
        client.server.value = "https://one.example.test"
        client.show(session, grants)
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) }
            }
        }
        rule.waitForIdle()
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick()
        // A drop: the socket's origin goes, the configured server stays.
        rule.runOnIdle { client.origin.value = null }
        rule.waitForIdle()
        rule.runOnIdle { client.origin.value = SHELL_TEST_ORIGIN }
        rule.waitForIdle()
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertIsOff()
        // Another server (I-2): nothing of the first one's cards is kept.
        rule.runOnIdle { client.server.value = "https://two.example.test" }
        rule.waitForIdle()
        // ta-coik.41 r2: another server's console starts with nothing selected (another web origin);
        // its chat of the same id is opened there.
        rule.runOnIdle { vm.selectSession("s1") }
        rule.waitForIdle()
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertIsOn()
    }
}
