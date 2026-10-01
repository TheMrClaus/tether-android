package com.tether.app.ui.inspector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.client.TetherClient
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.shell.ShellConsentClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ta-dl4: the inspector's reads re-send on the web's own dependency lists (dashboard.tsx:794-828):
 * the diff on the session id; the scripts on the id and the worktree; the change request on the id
 * and the worktree's mode. Nothing goes out while the link is down, and an unrelated session update
 * re-sends nothing.
 */
@RunWith(RobolectricTestRunner::class)
class InspectorReadsTest {
    @get:Rule val rule = createComposeRule()

    private class Recording(base: TetherClient = ShellConsentClient()) : TetherClient by base {
        val sent = ArrayList<String>()
        override fun requestWorktreeDiff(sessionId: String): Boolean { sent += "diff:$sessionId"; return true }
        override fun requestWorktreeScripts(sessionId: String): Boolean { sent += "scripts:$sessionId"; return true }
        override fun requestChangeRequest(sessionId: String, refresh: Boolean): Boolean { sent += "cr:$sessionId"; return true }
    }

    private val worktree = WorktreeInfo(path = "/w/a", branch = "b", status = "active", mode = "branch-off", setupStatus = "running")

    private fun drain(client: Recording): List<String> {
        rule.waitForIdle()
        return client.sent.toList().also { client.sent.clear() }
    }

    @Test
    fun eachReadFollowsTheWebsDependencyList() {
        val client = Recording()
        var session by mutableStateOf<AgentSession?>(InspectorBoards.session(worktree = worktree))
        var connected by mutableStateOf(false)
        rule.setContent { InspectorReads(client, session, connected) }

        // Not on a down link.
        assertEquals(emptyList<String>(), drain(client))
        connected = true
        assertEquals(listOf("diff:s1", "scripts:s1"), drain(client))

        // An unrelated session update (name, status, updatedAt) re-sends nothing.
        session = session!!.copy(name = "renamed", status = "running", updatedAt = InspectorBoards.NOW + 1)
        assertEquals(emptyList<String>(), drain(client))

        // The worktree changes (setup finished): the scripts again, nothing else.
        session = session!!.copy(worktree = worktree.copy(setupStatus = "ok"))
        assertEquals(listOf("scripts:s1"), drain(client))

        // The mode becomes checkout-pr: the worktree changed too, so the scripts and the change request.
        session = session!!.copy(worktree = worktree.copy(setupStatus = "ok", mode = "checkout-pr", prNumber = 12))
        assertEquals(listOf("scripts:s1", "cr:s1"), drain(client))

        // The link drops (nothing goes out) and comes back: every read again.
        connected = false
        assertEquals(emptyList<String>(), drain(client))
        connected = true
        assertEquals(listOf("diff:s1", "scripts:s1", "cr:s1"), drain(client))

        // Another session: its reads, by its id.
        session = session!!.copy(id = "s2", worktree = null)
        assertEquals(listOf("diff:s2"), drain(client))

        // No session open: nothing.
        session = null
        assertEquals(emptyList<String>(), drain(client))
    }
}
