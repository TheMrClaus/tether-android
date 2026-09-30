package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.ui.SessionDrawer
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.2 end to end: the resume picker is the sidebar's history rows (session-sidebar.tsx
 * onReopenHistory → dashboard.tsx reopen → use-tether.ts resumeHistory). A tap on a history-only
 * row sends `resume`, marks it seen, closes the drawer and keeps that row the selected one while
 * the previous chat is let go; the server's unicast `created` reply then opens the new session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ResumePickerBehaviourTest {
    @get:Rule val rule = createComposeRule()
    private val F = SidebarFixtures

    private fun row(prefix: String) = rule.onNode(
        SemanticsMatcher("row $prefix") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(prefix) } == true },
    )

    @Test fun tappingAHistoryRowResumesItAndTheCreatedReplyOpensTheSession() {
        val previous = F.live("s-prev", "Earlier chat", cwd = F.APP, ago = 2, historyId = "hist-prev")
        val client = RecordingClient(sessions = listOf(previous))
        client.historiesByCwd.value = mapOf(
            F.ROOT to listOf(
                F.history("hist-prev", "Earlier chat", cwd = F.APP, ago = 2),
                F.history("hist-old", "Old conversation", cwd = F.APP, ago = 60 * 24).copy(profileId = "work"),
            ),
        )
        val vm = TetherViewModel(client)
        vm.selectSession(previous.id)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        var closed = 0

        rule.setContent {
            val sessions by client.sessions.collectAsStateWithLifecycle()
            val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                SessionDrawer(
                    vm = vm,
                    prefs = prefs,
                    sessions = sessions,
                    selectedId = selectedId,
                    workspaceRoot = F.ROOT,
                    onSelect = vm::selectSession,
                    onClose = { closed++ },
                )
            }
        }
        rule.waitForIdle()
        row("Earlier chat").assertIsSelected()
        client.frames.clear()

        // A history-only row reads as history ("… ago", no status word; session-sidebar.tsx:173
        // still gives it the Chat tag) and a tap resumes it.
        val described = row("Old conversation").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(described, described.contains(" ago") && "Ready" !in described)
        row("Old conversation").performClick()
        rule.waitForIdle()
        val resume = client.frames.single { (it["type"] as JsonPrimitive).content == "resume" }
        assertEquals(frame("""{"type":"resume","historyId":"hist-old","cwd":"${F.APP}","profileId":"work"}"""), resume)
        assertTrue(client.types().toString(), "mark-seen" in client.types())
        assertEquals("the drawer closes", 1, closed)
        // dashboard.tsx:202-206: the resumed row is the selected one during the round trip.
        assertNull(vm.selectedSessionId.value)
        assertEquals("hist-old", vm.openingHistoryId.value)
        row("Old conversation").assertIsSelected()
        row("Earlier chat").assert(SemanticsMatcher("not selected") { it.config.getOrNull(SemanticsProperties.Selected) != true })
        assertTrue("nothing attaches before the reply", "attach" !in client.types())

        // The unicast `created` reply opens the resumed session (and attaches it once).
        client.created(F.live("s-new", "Old conversation", cwd = F.APP, ago = 0, historyId = "hist-old"))
        rule.waitForIdle()
        assertEquals("s-new", vm.selectedSessionId.value)
        assertNull(vm.openingHistoryId.value)
        assertEquals(1, client.frames.count { (it["type"] as JsonPrimitive).content == "attach" && (it["sessionId"] as JsonPrimitive).content == "s-new" })
        row("Old conversation").assertIsSelected()
    }
}
