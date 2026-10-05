package com.tether.app.ui.sidebar

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.42 (dashboard.tsx 90fbb9f :638-639): a history row is open (so neither unread nor digested)
 * when its live session is the pick OR the pending target, the drawer reading both slots.
 * (One test class per process-wide DataStore file.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SessionDrawerPendingTargetTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    @Test fun aRowWhoseLiveSessionIsThePendingTargetIsOpenNotUnread() {
        val live = F.live("p1", "Linked job", cwd = F.APP, ago = 5, historyId = "hist-pending").copy(updatedAt = System.currentTimeMillis() - 60_000)
        val client = RecordingClient(sessions = listOf(live))
        client.historiesByCwd.value = mapOf(
            F.ROOT to listOf(F.history("hist-pending", "Linked job", cwd = F.APP, ago = 5).copy(updatedAt = live.updatedAt)),
        )
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                SessionDrawer(vm = vm, prefs = prefs, sessions = listOf(live), selectedId = null, workspaceRoot = F.ROOT, onSelect = {}, onClose = {})
            }
        }
        rule.waitForIdle()
        val unread = SemanticsMatcher("unread row") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Linked job, changed since you last looked") } == true
        }
        assertEquals(1, rule.onAllNodes(unread).fetchSemanticsNodes().size)
        rule.runOnIdle { vm.openSession("p1") }
        rule.waitForIdle()
        assertEquals("the pending target's row is open", 0, rule.onAllNodes(unread).fetchSemanticsNodes().size)
    }
}
