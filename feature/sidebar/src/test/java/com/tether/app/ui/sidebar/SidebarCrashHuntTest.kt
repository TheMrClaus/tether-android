package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.protocol.model.HistorySession
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b: the session list as a real account can hold it: no name, an enormous one, one long word, right-to-left, surrogate
 * halves and combining marks, many workspaces, delegates, handed-off and archived rows, history-only rows with a huge digest, a
 * query over all of it; composed as the phone drawer and as the expanded rail, at the default font and at 2.0x. None may throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
open class SidebarCrashHuntTest {
    @get:Rule val rule = createComposeRule()

    private val names = listOf(
        "", " ", "main", "x".repeat(6000), "word ".repeat(2000), "A_very_long_single_word_".repeat(80), "مرحبا بالعالم ".repeat(50), "\uD800", "a\uD83D",
        "é".repeat(500), "😀".repeat(400), "👩‍👩‍👧‍👦 family", "\u0000‮ evil", "line\nbreak\r\nname", "\t\t", "/srv/ws/a/b/c.kt", "ＡＢＣ".repeat(300),
        "- ".repeat(1000), "[x](y)", "<b>bold</b>", "%s %d {0}", "Fix the flaky retry test in the importer",
    )

    private val cwds = listOf(
        "/srv/ws", "/srv/ws/a", "/srv/ws/" + "deep/".repeat(200), "/", "", "/srv/ws/ü/日本語/😀", "/srv/ws/" + "w".repeat(5000), "relative/path", "/srv/ws/.worktrees/" + "b".repeat(300),
    )

    private fun <T> List<T>.pick(r: Random) = this[r.nextInt(size)]

    private fun sessions(r: Random): Pair<List<AgentSession>, Map<String, List<HistorySession>>> {
        val live = (0 until r.nextInt(1, 40)).map { i ->
            val parent = if (i > 0 && r.nextInt(4) == 0) "s${r.nextInt(i)}" else null
            AgentSession(
                id = "s$i", provider = listOf("claude", "codex", "opencode", "gemini", "", "x".repeat(200)).pick(r), name = names.pick(r), cwd = cwds.pick(r),
                status = listOf("ready", "active", "waiting", "exited", "starting", "weird").pick(r),
                startedAt = r.nextLong(0, SidebarFixtures.NOW), updatedAt = r.nextLong(0, SidebarFixtures.NOW + 1_000_000),
                lastMessageAt = if (r.nextBoolean()) r.nextLong(0, SidebarFixtures.NOW) else null,
                historyId = if (r.nextInt(3) == 0) "h${r.nextInt(8)}" else null,
                runtimeArchived = r.nextInt(6) == 0, parentSessionId = parent,
                handedOffTo = if (r.nextInt(8) == 0) "s${r.nextInt(i + 1)}" else null,
                pinned = r.nextInt(8) == 0, nameIsCustom = r.nextBoolean(),
            )
        }
        val histories = cwds.associateWith { cwd ->
            (0 until r.nextInt(0, 6)).map { j ->
                HistorySession(
                    historyId = "h${r.nextInt(8)}-$j", provider = listOf("claude", "codex").pick(r), name = names.pick(r), cwd = cwd,
                    updatedAt = r.nextLong(0, SidebarFixtures.NOW), createdAt = r.nextLong(0, SidebarFixtures.NOW), lastSeenAt = if (r.nextBoolean()) r.nextLong(0, SidebarFixtures.NOW) else null,
                    digest = if (r.nextBoolean()) HistoryDigest(r.nextInt(0, 1000), names.pick(r)) else null,
                )
            }
        }
        return live to histories
    }

    private var state by mutableStateOf<SidebarState?>(null)

    private fun hunt(layout: TetherLayoutClass) {
        val seeds = (System.getenv("CRASHHUNT_SEEDS") ?: "60").toInt()
        val base = (System.getenv("CRASHHUNT_BASE") ?: "1").toLong()
        rule.setContent { state?.let { SidebarUnderTest(TetherSkin.StudioDark, it, layout) } }
        for (s in 0 until seeds) {
            val seed = base + s
            val r = Random(seed)
            val (live, hist) = sessions(r)
            val queries = listOf("", "a", "x", "main", "😀", "/")
            try {
                state = SidebarFixtures.state(
                    live, pinned = cwds.filter { r.nextInt(3) == 0 }, current = cwds.pick(r).ifEmpty { "/" }, histories = hist,
                    collapsed = cwds.filter { r.nextInt(5) == 0 }, activeId = if (r.nextBoolean()) "s0" else null, query = queries.pick(r),
                    activeOnly = r.nextInt(6) == 0, unreadOnly = r.nextInt(6) == 0, connected = r.nextInt(5) != 0,
                )
                rule.waitForIdle()
            } catch (t: Throwable) {
                throw AssertionError("sidebar threw at seed=$seed layout=$layout", t)
            }
        }
    }

    @Test fun phoneDrawer() = hunt(TetherLayoutClass.Phone)

    @Test fun expandedRail() = hunt(TetherLayoutClass.Expanded)
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SidebarCrashHuntLargeFontTest : SidebarCrashHuntTest()
