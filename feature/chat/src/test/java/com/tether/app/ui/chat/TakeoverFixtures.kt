package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.HandoffBriefReading
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonObject

/**
 * T8.5 slice c: the `@` picker's Sessions on this project (chat-view.tsx 90fbb9f :2791-2802) around
 * [ComposerFixtures.session] (cwd "/w"): two idle sessions, one running, one waiting, and the rows the
 * web leaves out (another folder, a handed-off source, an archived one).
 */
object TakeoverFixtures {
    const val NOW = ComposerFixtures.T_START + 60 * 60_000L

    private fun s(id: String, name: String, provider: String = "codex", cwd: String = "/w", status: String = "ready", lastAt: Long, model: String? = null, handedOffTo: String? = null, archived: Boolean = false) =
        AgentSession(
            id = id, provider = provider, name = name, cwd = cwd, status = status, startedAt = 1, updatedAt = 1,
            lastMessageAt = lastAt, model = model, handedOffTo = handedOffTo, runtimeArchived = archived,
        )

    val older = s("s-old", "Fix the login flow", provider = "claude", lastAt = NOW - 3 * 3_600_000L, model = "claude-opus-5")
    val newer = s("s-new", "Refactor the parser", lastAt = NOW - 5 * 60_000L, model = "gpt-5.5")
    val running = s("s-run", "Write the migration", lastAt = NOW - 60_000L, status = "active")
    val waiting = s("s-wait", "Review the release", lastAt = NOW - 2 * 60_000L, status = "waiting", provider = "opencode")
    val elsewhere = s("s-else", "Other project", cwd = "/elsewhere", lastAt = NOW)
    val handedOff = s("s-gone", "Already taken over", lastAt = NOW, handedOffTo = "x")
    val archived = s("s-arch", "Archived one", lastAt = NOW, archived = true)

    val roster: List<AgentSession> = listOf(ComposerFixtures.session, older, newer, running, waiting, elsewhere, handedOff, archived)

    const val INSTRUCTION = "Resume the work of “Fix the login flow”.\n\n- [ ] Run the tests\n- [ ] Check the build"
    const val MARKDOWN = "# Fix the login flow\n\nGoal: make the login redirect work.\n\nLast turn: added a test."

    fun brief(sourceId: String = older.id) = HandoffBriefReading(sourceId, JsonObject(emptyMap()), MARKDOWN, INSTRUCTION)

    /** Recorded takeover actions; [briefs] is state the test sets (the server's reply). */
    class Recorder {
        val briefRequests = mutableListOf<String>()
        val handoffs = mutableListOf<Pair<String, String>>()
        val cleared = mutableListOf<String>()
        var handoffResult = true
        var briefs by mutableStateOf<Map<String, HandoffBriefReading>>(emptyMap())

        fun takeover(sessions: List<AgentSession> = roster) = ComposerTakeover(
            sessions = sessions,
            briefs = briefs,
            onRequestBrief = { id -> briefRequests += id; true },
            onHandoff = { id, text -> handoffs += id to text; handoffResult },
            onClearBrief = { id -> cleared += id },
            now = { NOW },
        )
    }
}
