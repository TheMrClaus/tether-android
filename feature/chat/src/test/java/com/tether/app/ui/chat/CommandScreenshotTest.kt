package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.3 states (DoD: 6 skins at phone size, the expanded layout where it differs, 1.3×):
 * `command` = the `!` command mode (flag, the red-edged well in the mono face, Send to agent +
 * Background); `foreground` = a foreground command running (Background + Stop in place of Queue +
 * Interrupt); `slash` = the palette fed by the CLI inventory (the blocked /exit last, "terminal
 * only"); `mention` = the `@` Agents picker; `delegate` = the delegate chip with its selects;
 * `panel` = the transcript's command panel, running and finished-failed.
 */
enum class CommandShot(val id: String) {
    Command("command"),
    Foreground("foreground"),
    Slash("slash"),
    Mention("mention"),
    Delegate("delegate"),
    Panel("panel"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

fun ComposeContentTestRule.snapCommand(shot: CommandShot, skin: TetherSkin, name: String, size: String, width: Dp? = null) {
    mainClock.autoAdvance = false
    if (shot == CommandShot.Panel) {
        val running = commandOutputView(panelBlock(CommandFixtures.running))!!
        val failed = commandOutputView(panelBlock(CommandFixtures.failed))!!
        setContent {
            TetherTheme(choiceFor(skin)) {
                androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(chatWellColor(com.tether.app.ui.theme.LocalTetherTokens.current))
                            .padding(12.dp)
                            .testTag(ComposerTag),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CommandOutputPanel(running)
                        CommandOutputPanel(failed)
                    }
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
        return
    }
    val fixture = when (shot) {
        CommandShot.Foreground -> CommandFixtures.running
        CommandShot.Slash -> CommandFixtures.inventory
        else -> ComposerFixtures.idle
    }
    val draft = when (shot) {
        CommandShot.Command -> "!npm test -- --runInBand"
        CommandShot.Slash -> "/"
        CommandShot.Mention -> "Ask @"
        CommandShot.Delegate -> "Ask @cla"
        else -> ""
    }
    val rec = CommandFixtures.Recorder()
    setContent {
        ComposerHost(skin, width) {
            Composer(
                session = ComposerFixtures.session,
                projection = fixture.projection,
                controls = SessionControlFixtures.claudeIdleControls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                initialDraft = draft,
                tree = fixture.tree,
                controlActions = SessionControlFixtures.Recorder().actions(),
                runActions = rec.actions(agents = CommandFixtures.catalog),
            )
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot == CommandShot.Delegate) {
        mainClock.autoAdvance = true
        onNodeWithContentDescription("Claude, Opus 5, delegate").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitForIdle()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-$size.png", roborazziOptions = exact)
}

private fun panelBlock(f: ChatFixtures.Folded) =
    buildChatItems(f.projection, f.tree, showThinking = false).filterIsInstance<ChatItem.Block>().single { it.block.kind == COMMAND_OUTPUT_BLOCK }.raw

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class CommandPhoneScreenshotTest(private val shot: CommandShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun command() = rule.snapCommand(shot, skin, "commands-${shot.id}", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = CommandShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web desktop composer (1280×800): the keys carry their legends ("Send to agent", "Background", "Stop"). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class CommandTabletScreenshotTest(private val shot: CommandShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun command() = rule.snapCommand(shot, skin, "commands-${shot.id}", "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(CommandShot.Command, CommandShot.Foreground).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class CommandFontScaleScreenshotTest(private val shot: CommandShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun command() = rule.snapCommand(shot, skin, "commands-${shot.id}-font-1.3x", "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = CommandShot.entries.flatMap { s -> listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) } }
    }
}
