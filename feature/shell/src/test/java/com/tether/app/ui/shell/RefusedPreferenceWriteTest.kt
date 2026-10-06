package com.tether.app.ui.shell

import com.tether.app.testsupport.runPrefsWrite

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntSize
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.draft.DraftFixtures
import com.tether.app.ui.draft.DraftTestClient
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.sidebar.SidebarTags
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-8yn9: the real MainShell over preferences whose disk refuses every write. A folder chosen
 * from the welcome's Open workspace (WorkspacePickerHost) and from the drawer's Add workspace (the
 * drawer's controller) is pinned and made current all the same, held in memory like the web's
 * best-effort localStorage save, and nothing crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class RefusedPreferenceWriteTest {
    @get:Rule val rule = createComposeRule()

    private val disk = RefusingPrefsStore()
    private val prefs = UiPrefs.on(disk)
    private val client = DraftTestClient()
    private val vm by lazy { TetherViewModel(client) }

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(412, 915)
    }

    private fun until(what: String, condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        until("tag $tag") { exists(tag) }
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun pickerShown() = rule.onAllNodesWithText("Choose a folder").fetchSemanticsNodes().isNotEmpty()

    // ta-coik.52: the pins of the server the shell is signed in to (the web's preferences are per origin).
    private fun pinned(): List<String> = runBlocking { prefs.preferences.first().forServer(com.tether.app.client.serverOrigin(client.serverUrl.value)).pinnedProjects }

    private fun choose(folder: String, open: () -> Unit) {
        client.directories.value = DirectoryListing(current = folder)
        open()
        until("the folder picker") { pickerShown() }
        rule.onNodeWithText("Use this folder").performSemanticsAction(SemanticsActions.OnClick)
        until("the picker closed") { !pickerShown() }
        until("$folder pinned (now ${pinned()})") { folder in pinned() }
        until("$folder current") { vm.currentWorkspace.value == folder }
    }

    @Test fun pinsFromTheWelcomeAndTheDrawerHoldWhenTheDiskRefusesThem() {
        runPrefsWrite { prefs.setLastView(com.tether.app.client.serverOrigin(DraftFixtures.SERVER), "sessions") }
        rule.setContent {
            TetherTheme { CompositionLocalProvider(LocalWindowInfo provides window) { MainShell(vm, prefs) } }
        }
        until("the welcome") { exists(StudioWelcomeTags.Root) }

        choose("${DraftFixtures.ROOT}/welcome") { tap(StudioWelcomeTags.OpenWorkspace) }
        val afterWelcome = disk.attempts
        assertTrue("the pin was written, and refused", afterWelcome > 0)

        choose("${DraftFixtures.ROOT}/drawer") {
            if (!exists(SidebarTags.AddWorkspace)) tap(ShellTags.MenuKey)
            tap(SidebarTags.AddWorkspace)
        }
        assertTrue("the drawer's pin was written, and refused", disk.attempts > afterWelcome)
        // Both pins are still shown: the second write was made on top of the first one kept in memory.
        assertTrue(pinned().toString(), pinned().containsAll(listOf("${DraftFixtures.ROOT}/welcome", "${DraftFixtures.ROOT}/drawer")))
    }
}
