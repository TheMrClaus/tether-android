package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The T6.4 visual states. `session` = the web's subagent-runs(-top) scenario (the tab strip over
 * the Session tab: the closed roster, the transcript with its collapsed "2 agents" group);
 * `roster-open` the roster expanded; `run` and `run-error` the two runs' tabs; `spawned` a
 * running spawned Codex child (live output, the picture it was handed); `thread` a Codex child
 * known only from its lifecycle; `deck` / `deck-open` the composer deck's todo bar (closed /
 * open) over the running background command with its Stop key; `chips` the finished commands in
 * the transcript; `output` a running command's output sheet. The web seeder can freeze only the
 * first; the rest are built from the reducer corpus's event shapes (SubagentFixtures).
 */
enum class SubagentShot(val id: String) {
    Session("session"),
    RosterOpen("roster-open"),
    Run("run"),
    RunError("run-error"),
    Spawned("spawned"),
    Thread("thread"),
    Deck("deck"),
    DeckOpen("deck-open"),
    Chips("chips"),
    Output("output"),
}

private const val CaptureAtMs = 600L

private fun runFor(shot: SubagentShot): Pair<ChatFixtures.Folded, String?> = when (shot) {
    SubagentShot.Session, SubagentShot.RosterOpen -> SubagentFixtures.web to null
    SubagentShot.Run -> SubagentFixtures.web to "t1::toolu_a"
    SubagentShot.RunError -> SubagentFixtures.web to "t1::toolu_b"
    SubagentShot.Spawned -> SubagentFixtures.running to "spawn::run-1"
    SubagentShot.Thread -> SubagentFixtures.codexThread to "thread::thread-rev"
    else -> SubagentFixtures.activity to null
}

private val liveActions = CommandActions(stopLock = null, onOpen = {}, onStop = { com.tether.app.client.StopCommandResult.NotConnected })

fun ComposeContentTestRule.snapSubagents(shot: SubagentShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val listState = LazyListState()
    val (fixture, runId) = runFor(shot)
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                val t = LocalTetherTokens.current
                when (shot) {
                    SubagentShot.Deck, SubagentShot.DeckOpen -> Column(
                        Modifier.fillMaxSize().padding(t.css.spaceSm),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        selectProgress(fixture.tree)?.let { TodoBar(it, "s1") }
                        RunningCommandsBar(runningBackgroundCommands(fixture.tree), liveActions)
                    }
                    SubagentShot.Output -> Box(Modifier.fillMaxSize().padding(t.css.spaceMd)) {
                        CommandOutputSurface(runningBackgroundCommands(fixture.tree).single(), liveActions, onClose = {})
                    }
                    SubagentShot.Chips -> ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                    )
                    else -> {
                        val runs = collectSubagentRuns(fixture.tree)
                        val active = runs.firstOrNull { it.runId == runId }
                        Column(Modifier.fillMaxSize()) {
                            SubagentTabs(runs, active?.runId, onSelect = {})
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (active != null) {
                                    SubagentRunTab(active, showThinking = false, pending = emptyList(), pendingQuestions = emptyList(), answeredIds = emptySet())
                                } else {
                                    ChatTranscript(
                                        projection = fixture.projection,
                                        tree = fixture.tree,
                                        showThinking = false,
                                        onFetchTurns = { _, _ -> },
                                        zone = ChatFixtures.zone,
                                        listState = listState,
                                        roster = { SubagentRoster(runs, activeRunId = null, onSelect = {}) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    mainClock.autoAdvance = true
    when (shot) {
        SubagentShot.Session -> runOnIdle { runBlocking { listState.scrollToItem(0) } }
        SubagentShot.RosterOpen -> {
            runOnIdle { runBlocking { listState.scrollToItem(0) } }
            onNodeWithTag("subrun-roster").performClick()
        }
        SubagentShot.DeckOpen -> onNodeWithTag("todo-bar-head").performClick()
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

/** Every T6.4 state × both Studio skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SubagentPhoneScreenshotTest(private val shot: SubagentShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun subagents() = rule.snapSubagents(shot, skin, "subrun-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SubagentShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout (wider tabs, 0.8rem labels, 94% cards in the transcript). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SubagentTabletScreenshotTest(private val shot: SubagentShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun subagents() = rule.snapSubagents(shot, skin, "subrun-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SubagentShot.Session, SubagentShot.Run).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the surfaces (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SubagentFontScaleScreenshotTest(private val shot: SubagentShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun subagents() = rule.snapSubagents(shot, skin, "subrun-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SubagentShot.Session, SubagentShot.Run, SubagentShot.DeckOpen, SubagentShot.Output).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
