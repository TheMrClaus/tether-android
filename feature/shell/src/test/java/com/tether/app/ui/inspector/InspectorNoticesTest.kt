package com.tether.app.ui.inspector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.screenshots.ScreenSize
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.statusline.screenshots.snapBoard
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale

/** The inspector limit + MCP readings' fixtures, folded by the real reducer. */
object InspectorFixtures {
    const val NOW: Long = 1_790_078_400_000
    val env: () -> ReadingEnv = { ReadingEnv(NOW.toDouble(), Locale.US, ZoneOffset.UTC) }

    fun rateLimited(status: String = "rejected", resetsIn: Long = 95 * 60_000L): SessionView = SessionView(
        foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = NOW - 1_000),
            ev("rate_limit", "t1", seq = 2, ts = NOW) { put("status", status); put("limitType", "seven_day"); put("resetsAt", NOW + resetsIn) },
            ev("turn_end", "t1", seq = 3, ts = NOW) { put("outcome", "ok") },
        ),
    )

    val mcp: SessionView by lazy {
        SessionView(
            foldTree(
                freshTree(),
                evNullTurn("mcp_health_updated", seq = 1, ts = NOW) { put("name", "linear"); put("status", "ready") },
                evNullTurn("mcp_health_updated", seq = 2, ts = NOW) { put("name", "github"); put("status", "needs-auth"); put("error", "Sign in to GitHub to use this server.") },
                evNullTurn("mcp_health_updated", seq = 3, ts = NOW) { put("name", "filesystem"); put("status", "failed"); put("failureReason", "spawn ENOENT") },
                evNullTurn("mcp_health_updated", seq = 4, ts = NOW) { put("name", "browser"); put("status", "starting") },
            ),
        )
    }

    val healthy: SessionView by lazy {
        SessionView(
            foldTree(
                freshTree(),
                evNullTurn("mcp_health_updated", seq = 1, ts = NOW) { put("name", "linear"); put("status", "ready") },
                evNullTurn("mcp_health_updated", seq = 2, ts = NOW) { put("name", "memory"); put("status", "disabled") },
            ),
        )
    }
}

/** T6.6: the inspector readings against inspector.tsx / mcp-health-card.tsx. */
class InspectorNoticesModelTest {
    @Test
    fun theRateLimitNoticeReadsLikeTheWeb() {
        val now = InspectorFixtures.NOW.toDouble()
        assertEquals("Rate limited — requests are being rejected (seven day) — resets in 1h 35m", rateLimitNoticeText(InspectorFixtures.rateLimited(), now))
        assertEquals("Approaching the rate limit (seven day) — resets in 1h 35m", rateLimitNoticeText(InspectorFixtures.rateLimited("allowed_warning"), now))
        assertNull(rateLimitNoticeText(InspectorFixtures.rateLimited("allowed"), now))
        // Past its reset the notice expires on its own (the CLI may never send "allowed").
        assertNull(rateLimitNoticeText(InspectorFixtures.rateLimited(), now + 96 * 60_000))
        assertNull(rateLimitNoticeText(null, now))
    }

    @Test
    fun serversAreSortedByNameAndCountedLikeTheCompactCard() {
        val servers = mcpServers(InspectorFixtures.mcp)
        assertEquals(listOf("browser", "filesystem", "github", "linear"), servers.map { it.name })
        assertEquals("2 issues", mcpCountText(servers))
        assertEquals("spawn ENOENT", servers[1].error)
        assertEquals("1/2 ready", mcpCountText(mcpServers(InspectorFixtures.healthy)))
        assertEquals("Needs authentication", mcpStatusCopy("needs-auth"))
        assertEquals("Unknown", mcpStatusCopy("something-new"))
    }

    @Test
    fun aNameWithHiddenCodePointsIsDrawnWithThemAsVisibleTokens() {
        // "github" with a zero-width space, and one with a right-to-left override: neither may pass
        // for the plain "github", and each keeps its own row state (keyed on the raw name).
        val state = SessionView(
            foldTree(
                freshTree(),
                evNullTurn("mcp_health_updated", seq = 1, ts = InspectorFixtures.NOW) { put("name", "github"); put("status", "ready") },
                evNullTurn("mcp_health_updated", seq = 2, ts = InspectorFixtures.NOW) { put("name", "git​hub"); put("status", "failed"); put("error", "boom") },
                evNullTurn("mcp_health_updated", seq = 3, ts = InspectorFixtures.NOW) { put("name", "‮buhtig"); put("status", "ready") },
            ),
        )
        // T9.1: the name is kept raw and drawn by the one-line rule (com.tether.app.ui.text): every
        // hidden or reordering code point is a visible token, so the three never display alike.
        val servers = mcpServers(state)
        val shown = servers.associate { it.key to com.tether.app.ui.text.SafeText.line(it.name) }
        assertEquals("github", shown["github"])
        assertEquals(true, shown["git\u200Bhub"]!!.contains("⟨U+200B⟩"))
        assertEquals(true, shown["\u202Ebuhtig"]!!.contains("⟨U+202E⟩"))
        assertEquals(3, shown.values.toSet().size)
        assertEquals(3, servers.map { it.key }.toSet().size)
        // The error keeps its words raw for the prose rule.
        assertEquals("boom", servers.single { it.key == "git\u200Bhub" }.error)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorNoticesBehaviourTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theCompactCardOpensItselfWhileAServerHasAProblemAndSaysSoInWords() {
        rule.setContent { TetherTheme(choiceFor(TetherSkin.StudioDark)) { Column { InspectorMcpHealth("claude", null, InspectorFixtures.mcp) } } }
        rule.onAllNodesWithTag("mcp-server").assertCountEquals(4)
        rule.onNodeWithText("Needs authentication").assertExists()
        // Errors wait behind "View error" (one per troubled server), then show on a tap.
        rule.onAllNodesWithText("View error").assertCountEquals(2)
        rule.onNodeWithText("spawn ENOENT").assertDoesNotExist()
        rule.onAllNodesWithText("View error")[0].performClick()
        rule.onNodeWithText("spawn ENOENT").assertExists()
    }

    @Test
    fun aHealthyCardStartsClosedAndOpensOnATap() {
        rule.setContent { TetherTheme(choiceFor(TetherSkin.StudioDark)) { Column { InspectorMcpHealth("claude", null, InspectorFixtures.healthy) } } }
        rule.onAllNodesWithTag("mcp-server").assertCountEquals(0)
        rule.onNodeWithText("MCP health").performClick()
        rule.onAllNodesWithTag("mcp-server").assertCountEquals(2)
    }

    @Test
    fun opencodeWithoutServeHasNoCardAndServeShowsPlugins() {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                Column {
                    InspectorMcpHealth("opencode", null, InspectorFixtures.mcp)
                    InspectorMcpHealth("opencode", "opencode-serve-v2", InspectorFixtures.healthy)
                }
            }
        }
        rule.onNodeWithText("Plugins").assertExists()
        rule.onNodeWithText("2 LOADED").assertExists()
        rule.onAllNodesWithTag("mcp-health").assertCountEquals(1)
    }

    @Test
    fun theRateLimitNoticeIsAPoliteStatus() {
        rule.setContent { TetherTheme(choiceFor(TetherSkin.StudioDark)) { Column { InspectorLimitNotice(InspectorFixtures.rateLimited(), env = InspectorFixtures.env) } } }
        rule.onNodeWithTag("inspector-rate-limit")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }
}

enum class InspectorShot(val id: String) { Mcp("mcp"), Plugins("plugins"), RateLimit("rate-limit") }

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapInspector(shot: InspectorShot, skin: TetherSkin, board: String) =
    snapBoard(board, skin, ScreenSize.Phone, reducedMotion = true) {
        // The inspector sheet's body width on a phone (the telemetry sheet, 22rem).
        Column(Modifier.width(352.dp)) {
            when (shot) {
                InspectorShot.Mcp -> InspectorMcpHealth("claude", null, InspectorFixtures.mcp, Modifier.fillMaxWidth())
                InspectorShot.Plugins -> InspectorMcpHealth("opencode", "opencode-serve-v2", InspectorFixtures.healthy, Modifier.fillMaxWidth())
                InspectorShot.RateLimit -> InspectorLimitNotice(InspectorFixtures.rateLimited(), env = InspectorFixtures.env)
            }
        }
    }

/** T6.6 inspector states × all 6 skins (phone: the telemetry sheet; the tablet column is the same body). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorNoticesScreenshotTest(private val shot: InspectorShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun inspector() = rule.snapInspector(shot, skin, "inspector-${shot.id}")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = InspectorShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class InspectorNoticesFontScaleScreenshotTest(private val shot: InspectorShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun inspector() = rule.snapInspector(shot, skin, "inspector-${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(InspectorShot.Mcp, InspectorShot.RateLimit).flatMap { s -> listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) } }
    }
}
