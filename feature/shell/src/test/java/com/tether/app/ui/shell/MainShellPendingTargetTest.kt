package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.LastOpenedSession
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.42: the web's pending target through the real shell (dashboard.tsx 90fbb9f :292, :710-717). A
 * link to a chat not listed yet leaves the chat on screen (and attached) until the target is listed,
 * which then mounts (attaches) and is remembered; with nothing listed it reads "Reopening your session"
 * (:1702), with a list that lacks it the welcome stage shows and nothing is picked (:756); once listed
 * its block becomes current (:1153-1160); and its listing moves no view (nothing is pushed then).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellPendingTargetTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private val prefs = UiPrefs(ApplicationProvider.getApplicationContext())

    private fun chat(id: String, cwd: String = "/w") =
        AgentSession(id = id, provider = "claude", name = id, cwd = cwd, status = "ready", startedAt = 1, updatedAt = 1, historyId = "h-$id")

    private fun storedState(view: String) = runPrefsWrite {
        prefs.updatePreferences {
            it.copy(lastOpenedSession = null, lastOpenedByOrigin = emptyMap(), defaultWorkspace = "", showEndedSessions = true, pinnedProjects = emptyList())
        }
        prefs.setLastView(null, view)
    }

    private fun list(client: ShellConsentClient, vararg sessions: AgentSession) {
        rule.runOnIdle {
            for (s in sessions.reversed()) client.show(s, freshTree())
            client.sessions.value = sessions.toList()
        }
        rule.waitForIdle()
    }

    private fun compose(vm: TetherViewModel) {
        rule.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
    }

    private fun remembered(): LastOpenedSession? = runBlocking { prefs.preferences.first().lastOpenedFor(null) }

    private fun reopeningShown() = rule.onAllNodesWithText("Reopening your session.").fetchSemanticsNodes().isNotEmpty()

    private fun chatShown() = rule.onAllNodesWithTag(ShellTags.WorkspaceHeader, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun selected(destination: TopBarDestination) =
        rule.onNodeWithTag(ShellTags.nav(destination)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    /** A Sessions boot whose one-time pick put "a" on screen. */
    private fun aOnScreen(client: ShellConsentClient): TetherViewModel {
        storedState("sessions")
        list(client, chat("a"), chat("b"))
        val vm = TetherViewModel(client)
        compose(vm)
        rule.waitUntil(5_000) { client.mountCalls.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(listOf("a"), client.mountCalls.toList())
        return vm
    }

    @Test
    fun aLinkToAChatNotListedYetKeepsTheChatOnScreenUntilTheTargetIsListed() {
        val client = ShellConsentClient()
        val vm = aOnScreen(client)
        rule.waitUntil(5_000) { remembered()?.sessionId == "a" }
        rule.runOnIdle { vm.openSession("x") }
        rule.waitForIdle()
        assertEquals("the chat on screen stays (and nothing is attached)", listOf("a"), client.mountCalls.toList())
        assertTrue("a chat, not the welcome stage", chatShown())
        assertFalse(reopeningShown())
        assertEquals("still the chat on screen", "a", remembered()?.sessionId)

        list(client, chat("a"), chat("b"), chat("x"))
        assertEquals("the target is shown and its mount attaches it", listOf("a", "x"), client.mountCalls.toList())
        rule.waitUntil(5_000) { remembered()?.sessionId == "x" }
    }

    @Test
    fun anUnlistedTargetReadsAsReopeningWithNoListAndBlocksThePickOnceOneArrives() {
        storedState("sessions")
        val client = ShellConsentClient()
        client.sessions.value = emptyList()
        val vm = TetherViewModel(client)
        compose(vm)
        rule.runOnIdle { vm.openSession("x") }
        rule.waitForIdle()
        assertTrue(reopeningShown())

        list(client, chat("a"))
        assertFalse("a list without it: the welcome stage", reopeningShown())
        assertFalse(chatShown())
        assertTrue("nothing is picked while a target is pending", client.mountCalls.isEmpty())
        assertNull(vm.activeId.value)

        list(client, chat("a"), chat("x"))
        assertEquals(listOf("x"), client.mountCalls.toList())
    }

    @Test
    fun aListedTargetsBlockBecomesTheCurrentWorkspace() {
        val client = ShellConsentClient()
        val vm = aOnScreen(client)
        val before = vm.currentWorkspace.value
        rule.runOnIdle { vm.openSession("x") }
        rule.waitForIdle()
        assertEquals("not listed yet: nothing moves", before, vm.currentWorkspace.value)
        list(client, chat("a"), chat("b"), chat("x", cwd = "/elsewhere"))
        rule.waitUntil(5_000) { vm.currentWorkspace.value == "/elsewhere" }
    }

    /**
     * ta-coik.54 (WebSelection's pinned workspaces read): the block that owns a listed target is found among
     * the signed-in server's OWN pins (the web's preferences are per origin), never another server's or the
     * device-wide ones. "/proj" is pinned on server A only: its "/proj/sub" chat belongs to "/proj" there,
     * and on server B (no pin of its own) to itself.
     */
    private fun pinsOf(a: List<String>, b: List<String>) = runPrefsWrite {
        prefs.updatePreferences { it.copy(preferencesByOrigin = emptyMap(), pinnedProjects = emptyList()) }
        prefs.updatePreferencesFor("https://a.example:443") { it.copy(pinnedProjects = a) }
        prefs.updatePreferencesFor("https://b.example:443") { it.copy(pinnedProjects = b) }
    }

    private fun currentAfterOpeningSubTargetOn(server: String): String? {
        val client = ShellConsentClient().also { it.server.value = server }
        val vm = aOnScreen(client)
        rule.runOnIdle { vm.openSession("x") }
        rule.waitForIdle()
        list(client, chat("a"), chat("b"), chat("x", cwd = "/proj/sub"))
        rule.waitUntil(5_000) { vm.currentWorkspace.value?.startsWith("/proj") == true }
        return vm.currentWorkspace.value
    }

    @Test
    fun aListedTargetsOwningBlockIsFoundAmongThisServersOwnPins() {
        pinsOf(a = listOf("/proj"), b = listOf("/elsewhere"))
        assertEquals("/proj", currentAfterOpeningSubTargetOn("https://a.example"))
    }

    @Test
    fun anotherServersPinsDoNotOwnTheTarget() {
        pinsOf(a = listOf("/proj"), b = listOf("/elsewhere"))
        assertEquals("/proj/sub", currentAfterOpeningSubTargetOn("https://b.example"))
    }

    @Test
    fun theTargetBeingListedMovesNoView() {
        val client = ShellConsentClient()
        val vm = aOnScreen(client)
        rule.runOnIdle { vm.openSession("x") }
        rule.waitForIdle()
        assertTrue(selected(TopBarDestination.Sessions))
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Overview)).performClick()
        rule.waitForIdle()
        list(client, chat("a"), chat("b"), chat("x"))
        assertTrue("still the Overview", selected(TopBarDestination.Overview))
        assertEquals("nothing mounts off Sessions", listOf("a"), client.mountCalls.toList())
        rule.onNodeWithTag(ShellTags.nav(TopBarDestination.Sessions)).performClick()
        rule.waitForIdle()
        assertEquals("back on Sessions: the target", listOf("a", "x"), client.mountCalls.toList())
    }
}
