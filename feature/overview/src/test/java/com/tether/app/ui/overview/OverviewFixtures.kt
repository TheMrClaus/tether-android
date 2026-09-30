package com.tether.app.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.tether.app.protocol.model.OverviewActivity
import com.tether.app.protocol.model.OverviewAttention
import com.tether.app.protocol.model.OverviewCard
import com.tether.app.protocol.model.OverviewCounts
import com.tether.app.protocol.model.OverviewExcerpt
import com.tether.app.protocol.model.OverviewFacets
import com.tether.app.protocol.model.OverviewNormalizedFilters
import com.tether.app.protocol.model.OverviewPending
import com.tether.app.protocol.model.OverviewPendingPanel
import com.tether.app.protocol.model.OverviewProgress
import com.tether.app.protocol.model.OverviewProviderFacet
import com.tether.app.protocol.model.OverviewWorkspace
import com.tether.app.protocol.model.OverviewWorkspaceFacet
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.protocol.overview.OverviewData
import com.tether.app.protocol.overview.OverviewPhase
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode

/**
 * The approved concept's illustrative data (design/mockups/tether-overview/studio-*.png), as the
 * v131 feed would carry it. Times are UTC; [NOW] is 14:33.
 */
object OverviewFixtures {
    /** 2026-09-28T14:33:00Z. */
    const val NOW = 1_790_605_980_000L
    private const val MIN = 60_000L

    private val tether = OverviewWorkspace("/srv/tether", "tether")
    private val cario = OverviewWorkspace("/srv/cario", "cario")

    val pending = OverviewPending(
        sessionId = "s-sync", requestId = "r-1", kind = "approval", createdAt = NOW - 4 * MIN,
        title = "Android session sync", provider = "claude", summary = "Run integration tests?", detail = "npm run test:integration",
    )

    val cards = listOf(
        OverviewCard(
            sessionId = "s-sync", nodeId = "n", seq = 40, title = "Android session sync", provider = "claude", providerLabel = "Claude Code",
            workspace = tether, cwd = "/srv/tether", branch = "feat/android-sync", status = "waiting", statusSince = NOW - 4 * MIN,
            turnStartedAt = NOW - 30 * MIN, attention = OverviewAttention("approval", "Approval needed: Bash", NOW - 4 * MIN),
            excerpt = OverviewExcerpt("request", "Approve Bash: npm run test:integration"), pending = listOf(pending),
        ),
        OverviewCard(
            sessionId = "s-proto", nodeId = "n", seq = 12, title = "Protocol compatibility", provider = "codex", providerLabel = "Codex",
            workspace = tether, cwd = "/srv/tether", branch = "fix/protocol", status = "running", turnStartedAt = NOW - 134 * MIN,
            excerpt = OverviewExcerpt("assistant", "Verifying reconnect behavior"), progress = OverviewProgress(428, 512, "checks passed"),
        ),
        OverviewCard(
            sessionId = "s-game", nodeId = "n", seq = 9, title = "Game tools", provider = "gemini", providerLabel = "Gemini CLI",
            workspace = cario, cwd = "/srv/cario", branch = "feat/game-tools", status = "running", turnStartedAt = NOW - 85 * MIN,
            excerpt = OverviewExcerpt("tool", "Bash: npm run test"),
        ),
        OverviewCard(
            sessionId = "s-docs", nodeId = "n", seq = 7, title = "Docs and site", provider = "opencode", providerLabel = "OpenCode",
            workspace = tether, cwd = "/srv/tether", branch = "docs/setup", status = "attention", statusSince = NOW - 42 * MIN,
            attention = OverviewAttention("error", "Turn failed: build exited 1"),
        ),
    )

    private fun activity(minAgo: Long, sessionId: String, title: String, provider: String, workspace: String, kind: String, text: String) =
        OverviewActivity(
            id = "n:$sessionId:${100 - minAgo}:$kind", ts = NOW - minAgo * MIN, sessionId = sessionId, nodeId = "n", seq = 100 - minAgo,
            title = title, provider = provider, workspace = workspace, kind = kind, text = text,
        )

    val activity = listOf(
        activity(0, "s-sync", "Android session sync", "claude", "tether", "request", "Approval requested: Bash — npm run test:integration"),
        activity(1, "s-proto", "Protocol compatibility", "codex", "tether", "turn_completed", "Completed: 428 protocol checks"),
        activity(2, "s-game", "Game tools", "gemini", "cario", "tool_started", "Bash: npm run test"),
        activity(4, "s-docs", "Docs and site", "opencode", "tether", "failure", "Turn failed: build exited 1"),
    )

    private val facets = OverviewFacets(
        workspaces = listOf(OverviewWorkspaceFacet("/srv/cario", "cario", 1), OverviewWorkspaceFacet("/srv/tether", "tether", 3)),
        providers = listOf(
            OverviewProviderFacet("claude", "claude", label = "Claude Code", count = 1),
            OverviewProviderFacet("codex", "codex", label = "Codex", count = 1),
        ),
    )

    val populatedData = OverviewData(
        filters = OverviewNormalizedFilters(emptyList(), emptyList(), listOf("running", "waiting", "attention")),
        page = 0, pageSize = 24, pageCount = 1, totalCards = 4,
        counts = OverviewCounts(running = 2, waiting = 1, attention = 1, ready = 3, workspaces = 2, total = 7),
        facets = facets, cards = cards, pending = OverviewPendingPanel(listOf(pending), 1, 0),
        activitySince = NOW - 180 * MIN, generatedAt = NOW,
    )

    val populated = OverviewClientState(
        phase = OverviewPhase.Live, feedId = "feed_1", cursor = 3, awaitingSnapshot = false,
        data = populatedData, activity = activity, updatedAt = NOW,
    )

    /** The node has no managed session. */
    val empty = OverviewClientState(
        phase = OverviewPhase.Live, feedId = "feed_1", cursor = 1, awaitingSnapshot = false,
        data = OverviewData(
            filters = OverviewNormalizedFilters(statuses = listOf("running", "waiting", "attention")),
            page = 0, pageSize = 24, pageCount = 1, totalCards = 0, activitySince = NOW - 180 * MIN, generatedAt = NOW,
        ),
        updatedAt = NOW,
    )

    /** The link dropped 12 minutes ago: the last data, marked stale. */
    val offline = populated.copy(phase = OverviewPhase.Offline, awaitingSnapshot = true, updatedAt = NOW - 12 * MIN)

    /** Subscribed, no snapshot yet. */
    val loading = OverviewClientState(phase = OverviewPhase.Loading)
}

fun choiceFor(skin: TetherSkin): ThemeChoice = ThemeChoice(skin.family, if (skin.isDark) ThemeMode.Dark else ThemeMode.Light)

@Composable
fun OverviewUnderTest(
    skin: TetherSkin,
    state: OverviewClientState,
    connected: Boolean = true,
    choice: OverviewChoice = OverviewChoice(),
    actions: OverviewActions = OverviewActions(),
    onChoice: (OverviewChoice) -> Unit = {},
) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            OverviewScreen(
                state = state,
                connected = connected,
                choice = choice,
                onChoice = onChoice,
                page = 0,
                onPage = {},
                now = OverviewFixtures.NOW,
                actions = actions,
            )
        }
    }
}
