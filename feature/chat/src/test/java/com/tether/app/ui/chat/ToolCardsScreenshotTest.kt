package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.ServerMessage
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tool-card states (T6.2 DoD), matched to the S0.4 web scenarios where one exists:
 * `tools` = tool-cards (the finished run collapsed with errors, the MCP card with its picture kept
 * apart), `tools-open` = the same run expanded (every Claude input renderer: raw JSON, the Edit /
 * MultiEdit / Write diffs, the failed Bash), `codex` = codex-tool-cards-top (the rich cards, the run
 * held open by its file change), `codex-details` (plan, turn diff, reviews), `running` (a live
 * Codex command streaming output and a Claude tool at 12s — the web seeder cannot freeze either),
 * `interrupted` (issue #184's stop state and its disclosure), `opencode` (the task card) and
 * `git-changes` (the repository panel's card with one file open).
 */
enum class ToolShot(val id: String) {
    Tools("tools"),
    ToolsOpen("tools-open"),
    Codex("codex"),
    CodexDetails("codex-details"),
    Running("running"),
    Interrupted("interrupted"),
    Opencode("opencode"),
    GitChanges("git-changes"),
}

private const val CaptureAtMs = 600L

private fun fixtureFor(shot: ToolShot): ChatFixtures.Folded = when (shot) {
    ToolShot.Tools, ToolShot.ToolsOpen -> ToolFixtures.tools
    ToolShot.Codex -> ToolFixtures.codexTools
    ToolShot.CodexDetails -> ToolFixtures.codexDetails
    ToolShot.Running -> ToolFixtures.running
    ToolShot.Interrupted -> ToolFixtures.corpusFinal("tool-lifecycle-progress")
    ToolShot.Opencode -> ToolFixtures.opencodeTask
    ToolShot.GitChanges -> ToolFixtures.tools // unused: the card renders alone
}

private val gitSummary = WorktreeDiffSummaryView(
    baseRef = "origin/main",
    commitsAhead = 2.0,
    committed = listOf(WorktreeDiffEntry("src/config.ts", "M"), WorktreeDiffEntry("src/very/long/path/to/a/deeply/nested/module/version.ts", "A")),
    uncommitted = listOf(WorktreeDiffEntry("README.md", " M"), WorktreeDiffEntry("logo.png", "??")),
)

private val gitDiffs = mapOf(
    "src/config.ts" to ServerMessage.GitDiffFile(
        "s1",
        "src/config.ts",
        "@@ -1,2 +1,2 @@\n-export const config = { retries: 3 };\n+export const config = { retries: 5, backoffMs: 250 };\n export default config;",
        truncated = false,
        binary = false,
    ),
)

fun ComposeContentTestRule.snapTools(shot: ToolShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val listState = LazyListState()
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                if (shot == ToolShot.GitChanges) {
                    Box(Modifier.fillMaxSize().padding(horizontal = LocalTetherTokens.current.css.spaceMd, vertical = LocalTetherTokens.current.css.spaceLg)) {
                        GitChangesCard(gitSummary, gitDiffs, onRequestFile = {})
                    }
                } else {
                    val fixture = fixtureFor(shot)
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        richCodex = shot == ToolShot.Codex || shot == ToolShot.CodexDetails || shot == ToolShot.Running,
                        richOpencode = shot == ToolShot.Opencode,
                    )
                }
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    mainClock.autoAdvance = true
    when (shot) {
        ToolShot.ToolsOpen, ToolShot.Interrupted -> {
            onNodeWithTag("tool-activity-group").performClick()
            waitForIdle()
            onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
            waitForIdle()
            onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group"))
            if (shot == ToolShot.Interrupted) onNodeWithText("What the CLI reported").performClick()
        }
        ToolShot.Codex -> {
            onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
            mainClock.advanceTimeBy(CaptureAtMs)
            runOnIdle { runBlocking { listState.scrollToItem(0) } }
        }
        ToolShot.GitChanges -> onNodeWithText("src/config.ts").performClick()
        else -> Unit
    }
    waitForIdle()
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every tool-card state × both Studio skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolCardsPhoneScreenshotTest(private val shot: ToolShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun tools() = rule.snapTools(shot, skin, "tool-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ToolShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout: 94% cards (nested cards 94% of their run), the 16rem clamp. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ToolCardsTabletScreenshotTest(private val shot: ToolShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun tools() = rule.snapTools(shot, skin, "tool-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ToolShot.Tools, ToolShot.ToolsOpen, ToolShot.Codex).flatMap { s ->
            TetherSkin.entries.map { arrayOf<Any>(s, it) }
        }
    }
}

/** PLAN §4: 1.3× font scale does not break the cards (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ToolCardsFontScaleScreenshotTest(private val shot: ToolShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun tools() = rule.snapTools(shot, skin, "tool-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ToolShot.ToolsOpen, ToolShot.Codex, ToolShot.GitChanges).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
