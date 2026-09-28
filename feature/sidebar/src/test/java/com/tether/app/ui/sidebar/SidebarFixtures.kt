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
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeFamily
import com.tether.app.ui.theme.ThemeMode

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

fun choiceFor(skin: TetherSkin): ThemeChoice = ThemeChoice(skin.family, if (skin.isDark) ThemeMode.Dark else ThemeMode.Light)

private val StudioRail = Color(0xFF141D2E)

/**
 * The host containers the sidebar lives in, reproduced for the goldens (feature/shell owns the
 * real ones): the phone drawer — `min(20rem, 88vw)` (Studio `min(21rem, 92vw)`), padded
 * `space-md` (Studio `1rem 0.875rem 0.75rem`), `--graphite` with a `1px --line` right edge (Studio
 * the fixed `#141d2e`) over the scrimmed page — and the expanded layout's 264dp rail column.
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
            val studio = t.skin.family == ThemeFamily.Studio
            BoxWithConstraints(Modifier.fillMaxSize().background(t.mineral)) {
                val phone = layout == TetherLayoutClass.Phone
                if (phone) Box(Modifier.fillMaxSize().background(t.scrim))
                val width = when {
                    !phone -> 264.dp
                    studio -> minOf(336.dp, maxWidth * 0.92f)
                    else -> minOf(320.dp, maxWidth * 0.88f)
                }
                val edge = if (phone) t.line else t.lineStrong
                Box(
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        .drawBehind {
                            drawRect(if (studio) StudioRail else t.graphite)
                            if (!studio) drawRect(edge, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                        }
                        .padding(end = if (studio) 0.dp else 1.dp)
                        .padding(
                            start = if (studio) 14.dp else t.css.spaceMd,
                            end = if (studio) 14.dp else t.css.spaceMd,
                            top = if (studio) (if (phone) 16.dp else 21.6.dp) else t.css.spaceMd,
                            bottom = if (studio) 12.dp else if (phone) t.css.spaceMd else t.css.spaceSm,
                        ),
                ) {
                    SessionSidebar(state, actions, layout = layout, seed = seed)
                }
            }
        }
    }
}
