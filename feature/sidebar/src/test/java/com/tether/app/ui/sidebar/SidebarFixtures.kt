package com.tether.app.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.helpers.JsCollator
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.SidebarSort
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import com.tether.app.ui.theme.mode

/** Seeded sidebar data, shaped like the web's `session-drawer` scenario (tether scripts/parity-seed.mjs). */
object SidebarFixtures {
    const val ROOT = "/srv/ws"
    const val APP = "/srv/ws/parity-app"
    const val DOCS = "/srv/ws/docs"
    const val NOW = 1_767_225_600_000L
    private const val MIN = 60_000L

    /** A deterministic ordinal collator (the key tie-break never decides these fixtures). */
    val collator = JsCollator { a, b -> a.compareTo(b) }

    fun live(
        id: String,
        name: String,
        cwd: String = APP,
        status: String = "ready",
        ago: Long,
        provider: String = "claude",
        historyId: String? = null,
        parent: String? = null,
        archived: Boolean = false,
        handedOffTo: String? = null,
    ) = AgentSession(
        id = id,
        provider = provider,
        name = name,
        cwd = cwd,
        status = if (archived) "exited" else status,
        startedAt = NOW - ago * MIN,
        updatedAt = NOW - ago * MIN,
        historyId = historyId,
        runtimeArchived = archived,
        parentSessionId = parent,
        handedOffTo = handedOffTo,
    )

    fun history(
        id: String,
        name: String,
        cwd: String = APP,
        ago: Long,
        provider: String = "claude",
        seenAgo: Long? = null,
        digest: HistoryDigest? = null,
    ) = HistorySession(
        historyId = id,
        provider = provider,
        name = name,
        cwd = cwd,
        updatedAt = NOW - ago * MIN,
        createdAt = NOW - ago * MIN,
        lastSeenAt = seenAgo?.let { NOW - it * MIN },
        digest = digest,
    )

    /** The web drawer shot: 11 seeded live chats under the root workspace. */
    val drawerSessions: List<AgentSession> = listOf(
        live("s01", "Worktree with a service", cwd = "$ROOT/repo", ago = 8),
        live("s02", "Add the version file", status = "waiting", ago = 9),
        live("s03", "Long task with notices", ago = 60),
        live("s04", "Review with sub-agents", ago = 120),
        live("s05", "Codex tool cards", provider = "codex", ago = 180),
        live("s06", "Apply the config edits", ago = 240),
        live("s07", "Tool cards", ago = 300),
        live("s08", "Long markdown", ago = 360),
        live("s09", "Conversation timeline", ago = 420),
        live("s10", "Approval pending", status = "waiting", ago = 480),
        live("s11", "Summarize the README in one line.", ago = 540),
    )

    /** Status rows: active (spinner), waiting (ping), unread + digest, history-only, handed off. */
    val statusSessions: List<AgentSession> = listOf(
        live("a1", "Refactor the retry loop", status = "active", ago = 1),
        live("a2", "Approve the lint fix", status = "waiting", ago = 3),
        live("a3", "Finished while you were away", ago = 12, historyId = "h-away"),
        live("a4", "Moved to a new session", ago = 50, handedOffTo = "a1"),
    )
    val statusHistories: Map<String, List<HistorySession>> = mapOf(
        ROOT to listOf(
            history("h-away", "Finished while you were away", ago = 12, seenAgo = 40, digest = HistoryDigest(3, "All four tests pass; the backoff is 250ms.")),
            history("h-old", "Earlier codex thread", provider = "codex", ago = 26 * 60, seenAgo = 30 * 60),
        ),
    )

    /** Several blocks: two kept workspaces + the current root; delegates, a folded block, archived rows. */
    val groupSessions: List<AgentSession> = listOf(
        live("g1", "Release checklist", cwd = DOCS, status = "waiting", ago = 2),
        live("g2", "Rewrite the install guide", cwd = DOCS, ago = 30),
        live("g3", "Build the parity app", cwd = APP, status = "active", ago = 4),
        live("g4", "Reviewer", cwd = APP, ago = 6, parent = "g3"),
        live("g5", "Test runner", cwd = APP, ago = 7, parent = "g3"),
        live("g6", "Scratch notes", cwd = ROOT, ago = 90),
        live("g7", "Old spike", cwd = APP, ago = 600, archived = true),
        live("g8", "Retired docs pass", cwd = DOCS, ago = 900, archived = true),
    )

    /** A 69-character name that cannot fit one phone line (the phone draws it on one line, cut with an ellipsis). */
    const val LONG_NAME = "Untangle the reconnect backoff so a flapping link stops nagging users"

    /** A name that is one overlong word: no space to break at, so the line ends in an ellipsis. */
    const val LONG_WORD = "Investigate_supercalifragilisticexpialidocious_checkpointing_regression"

    /** A location long enough that the phone row's meta line must cut it. */
    const val LONG_LOCATION = "$ROOT/repo/.worktrees/reconnect-backoff-for-the-flapping-link-regression"

    /**
     * The phone drawer's long-names board (ta-1jj7): a 69-character name, an overlong word, a short
     * "main", then active / waiting / idle, a worktree location, an unseen row with its digest, and a
     * handed-off one.
     */
    val longNameSessions: List<AgentSession> = listOf(
        live("l1", LONG_NAME, status = "active", ago = 1),
        live("l2", LONG_WORD, status = "waiting", ago = 3),
        live("l3", "main", ago = 5),
        live("l4", "Fix the flaky retry test in the importer", cwd = LONG_LOCATION, ago = 9),
        live("l5", "Finished while you were away, with a name that also runs long", ago = 12, historyId = "h-long"),
        live("l6", "Moved to a new session after the context filled up completely", ago = 50, handedOffTo = "l1"),
    )
    val longNameHistories: Map<String, List<HistorySession>> = mapOf(
        ROOT to listOf(
            history("h-long", "Finished while you were away, with a name that also runs long", ago = 12, seenAgo = 40, digest = HistoryDigest(3, "All four tests pass; the backoff is 250ms.")),
        ),
    )

    fun state(
        sessions: List<AgentSession>,
        pinned: List<String> = emptyList(),
        current: String = ROOT,
        histories: Map<String, List<HistorySession>> = emptyMap(),
        lastSeen: Map<String, Long> = emptyMap(),
        orders: Map<String, List<String>> = emptyMap(),
        collapsed: List<String> = emptyList(),
        activeId: String? = null,
        query: String = "",
        harness: String? = null,
        activeOnly: Boolean = false,
        unreadOnly: Boolean = false,
        connected: Boolean = true,
        openingHistoryId: String? = null,
    ): SidebarState {
        val workspaces = SidebarModel.sidebarWorkspaces(pinned, current)
        val rows = SidebarModel.sidebarSessions(
            visible = SidebarModel.visibleSessions(sessions, showEnded = true),
            historiesByCwd = histories,
            workspaces = workspaces,
            lastSeen = lastSeen,
            sessionOrders = orders,
            sort = SidebarSort.Created,
            activeId = activeId,
            openingHistoryId = openingHistoryId,
            collator = collator,
        )
        return SidebarState(
            connected = connected,
            currentWorkspace = current,
            workspaceRoot = ROOT,
            workspaces = workspaces,
            pinned = pinned,
            collapsed = collapsed,
            activity = SidebarModel.projectActivity(sessions, workspaces),
            sidebarSessions = rows,
            filteredSessions = SidebarModel.filteredSessions(rows, query, harness),
            query = query,
            harness = harness,
            activeOnly = activeOnly,
            unreadOnly = unreadOnly,
            sessionOrders = orders,
            activeSessionId = activeId,
            openingHistoryId = openingHistoryId,
            now = NOW,
        )
    }
}

fun choiceFor(skin: TetherSkin): ThemeMode = skin.mode

private val StudioRail = Color(0xFF141D2E)

/**
 * The host containers the sidebar lives in, reproduced for the goldens (feature/shell owns the
 * real ones). The phone drawer follows SessionDrawerHost in lockstep (ta-1jj7, owner-directed
 * design: the compact full-screen drawer; ta-8znp: the foot inset): an opaque full-width panel on the
 * fixed `#141d2e`, no backdrop, padded by [PhoneDrawer]'s design minimum per side (the system-bar and
 * cutout insets are zero here; the host's own test measures them). The expanded layout's 264dp rail
 * column keeps `1.35rem 0.875rem 0.75rem`.
 */
@Composable
fun SidebarUnderTest(
    skin: TetherSkin,
    state: SidebarState,
    layout: TetherLayoutClass = TetherLayoutClass.Phone,
    seed: SidebarUiSeed = SidebarUiSeed(),
    actions: SidebarActions = SidebarActions(),
) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            val t = LocalTetherTokens.current
            BoxWithConstraints(Modifier.fillMaxSize().background(t.mineral)) {
                val phone = layout == TetherLayoutClass.Phone
                val width = if (phone) maxWidth else 264.dp
                Box(
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        .drawBehind {
                            drawRect(StudioRail)
                        }
                        .then(
                            if (phone) {
                                Modifier.padding(
                                    start = PhoneDrawer.Edge,
                                    end = PhoneDrawer.Edge,
                                    top = PhoneDrawer.Edge,
                                    bottom = PhoneDrawer.Bottom,
                                )
                            } else {
                                Modifier.padding(start = 14.dp, end = 14.dp, top = 21.6.dp, bottom = 12.dp)
                            },
                        ),
                ) {
                    SessionSidebar(state, actions, layout = layout, seed = seed)
                }
            }
        }
    }
}
