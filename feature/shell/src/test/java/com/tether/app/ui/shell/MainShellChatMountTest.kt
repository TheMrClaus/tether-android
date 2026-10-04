package com.tether.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.39 r2: the web attaches a chat on every mount of its ChatView (use-tether.ts 90fbb9f
 * :1568; dashboard.tsx :1572-1647 mounts it only on Sessions, so coming back from the Overview,
 * Scheduled or the Usage route mounts it again). The shell reports the chat view on screen; a real
 * mount attaches it (the client sends `afterSeq` = cursor), a recreation or a re-selection of the
 * chat on screen does not mount anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1200dp-h1000dp-mdpi")
class MainShellChatMountTest {
    @get:Rule val rule = createComposeRule()

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(1200, 1000)
    }

    private fun session(id: String) =
        AgentSession(id = id, provider = "claude", name = id, cwd = "/w", status = "active", startedAt = 1, updatedAt = 1, historyId = "h-$id")

    private fun go(destination: TopBarDestination) {
        rule.onNodeWithTag(ShellTags.nav(destination)).performClick()
        rule.waitForIdle()
    }

    @Test
    fun returningToTheChatFromAnotherViewAttachesItAgainButARecreationDoesNot() {
        val client = ShellConsentClient()
        client.show(session("s1"), freshTree())
        client.show(session("s2"), freshTree())
        val vm = TetherViewModel(client)
        vm.selectSession("s1")
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        val restorer = StateRestorationTester(rule)
        restorer.setContent { TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } } }
        rule.waitForIdle()
        assertEquals("the open is the mount's attach", listOf("s1"), client.attachCalls.toList())

        // A recreation (rotation) composes the shell again on the same chat: no mount.
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("s1"), client.attachCalls.toList())

        // Re-selecting the chat on screen (the drawer): the web remounts nothing either.
        vm.selectSession("s1")
        rule.waitForIdle()
        assertEquals(listOf("s1"), client.mountCalls.toList())

        // Overview and back: ChatView mounts again on the web, so the chat is attached again.
        go(TopBarDestination.Overview)
        assertEquals(listOf("s1"), client.mountCalls.toList())
        go(TopBarDestination.Sessions)
        assertEquals(listOf("s1", "s1"), client.mountCalls.toList())

        // Scheduled and back: likewise.
        go(TopBarDestination.Scheduled)
        go(TopBarDestination.Sessions)
        assertEquals(listOf("s1", "s1", "s1"), client.mountCalls.toList())

        // The Usage page (another route on the web) and back: likewise.
        go(TopBarDestination.Usage)
        go(TopBarDestination.Sessions)
        assertEquals(listOf("s1", "s1", "s1", "s1"), client.mountCalls.toList())

        // Another chat: one attach (the open's, matched with its mount), and again no extra on a recreation.
        val before = client.attachCalls.size
        vm.selectSession("s2")
        rule.waitForIdle()
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("s2"), client.attachCalls.drop(before))
    }
}
