package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-a5jl: the compact activity rows and the detail sheet, one board per state. A row board is the transcript with its
 * open group (Shell done, Read done, Edit error, Search interrupted, Shell running), a Thinking row and a Read row with its
 * picture's tile. A sheet board shoots the sheet's own surface drawn in place over the dimmed transcript (a Dialog is a
 * separate window and is not in a node capture); what it holds is what a tap on that row opens.
 */
enum class ActivityShot(val dir: String) {
    Rows("activity-rows"),
    SheetShell("activity-sheet-shell"),
    SheetThinking("activity-sheet-thinking"),
    SheetEdit("activity-sheet-edit"),
    SheetRunning("activity-sheet-running"),
}

private const val ActivityCaptureAtMs = 600L

/** The rows board's fixture and the sheet boards': [shot] -> the transcript behind, the block the sheet is for (null = no sheet). */
private class ActivityBoard(val fixture: ChatFixtures.Folded, val sheetKey: String?, val richCodex: Boolean = false)

private fun boardFor(shot: ActivityShot): ActivityBoard = when (shot) {
    ActivityShot.Rows -> ActivityBoard(ActivityFixtures.rows, null)
    ActivityShot.SheetShell -> ActivityBoard(ActivityFixtures.finishedShell, keyOf(ActivityFixtures.finishedShell, "c1"))
    ActivityShot.SheetThinking -> ActivityBoard(ChatFixtures.thinking, "t1/t1:th0")
    ActivityShot.SheetEdit -> ActivityBoard(ActivityFixtures.edit, keyOf(ActivityFixtures.edit, "e1"))
    ActivityShot.SheetRunning -> ActivityBoard(ActivityFixtures.runningCommand, keyOf(ActivityFixtures.runningCommand, "cmd-live"), richCodex = true)
}

/** The sheet key of the tool block whose tool id is [toolId] (the reducer keys a tool block by its tool id). */
private fun keyOf(fixture: ChatFixtures.Folded, toolId: String): String = "${fixture.projection.turnOrder.first()}/$toolId"

/** The transcript, and over it (when [sheetKey] is set) the dimmed scrim and the sheet's surface. */
@androidx.compose.runtime.Composable
internal fun ActivityBoardContent(fixture: ChatFixtures.Folded, sheetKey: String?, richCodex: Boolean, showThinking: Boolean, docked: Boolean) {
    val t = LocalTetherTokens.current
    CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
        Box(Modifier.fillMaxSize()) {
            ChatTranscript(
                projection = fixture.projection,
                tree = fixture.tree,
                showThinking = showThinking,
                onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone,
                showTimeline = false,
                groupToggles = allGroupsOpen(fixture, richCodex),
                richCodex = richCodex,
            )
            val target = sheetKey?.let { activityTarget(fixture.projection, fixture.tree, it) }
            if (target != null) {
                Box(Modifier.fillMaxSize().background(dialogScrim(t)), contentAlignment = if (docked) Alignment.BottomCenter else Alignment.Center) {
                    ActivitySheetSurface(target, ToolRenderFlags(richCodex, richOpencode = false, showThinking = showThinking), docked = docked)
                }
            }
        }
    }
}

fun ComposeContentTestRule.snapActivity(
    shot: ActivityShot,
    skin: TetherSkin,
    dir: String,
    size: String,
    wellHeight: Dp,
    wellWidth: Dp? = null,
    docked: Boolean = true,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
) {
    mainClock.autoAdvance = false
    val board = boardFor(shot)
    setContent {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            ChatHost(skin, wellHeight, wellWidth) {
                ActivityBoardContent(board.fixture, board.sheetKey, board.richCodex, showThinking = true, docked = docked)
            }
        }
    }
    mainClock.advanceTimeBy(ActivityCaptureAtMs)
    waitForIdle()
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(ActivityCaptureAtMs)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$dir/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** The web's phone viewport (412x915): the rows, and the sheets docked at the foot. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivityPhoneScreenshotTest(private val shot: ActivityShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapActivity(shot, skin, shot.dir, "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> =
            listOf(ActivityShot.Rows, ActivityShot.SheetShell, ActivityShot.SheetThinking, ActivityShot.SheetEdit).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } } +
                listOf(arrayOf<Any>(ActivityShot.SheetRunning, TetherSkin.StudioDark))
    }
}

/** The desktop layout (1280x800): rows at the card width, the sheet a centred card up to 720 dp. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ActivityTabletScreenshotTest(private val shot: ActivityShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapActivity(shot, skin, shot.dir, "tablet", WellHeightTablet, WellWidthTablet, docked = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ActivityShot.Rows, ActivityShot.SheetShell, ActivityShot.SheetThinking).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** 360 dp at 1.3x: the rows. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 1.3f)
class ActivityFont13ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapActivity(ActivityShot.Rows, skin, "activity-rows-360-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** 360 dp at 2.0x: the rows wrap their state below the verb, and the sheet wraps the long command. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 2.0f)
class ActivityFont20ScreenshotTest(private val shot: ActivityShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapActivity(shot, skin, if (shot == ActivityShot.Rows) "activity-rows-360-font-2.0x" else "activity-sheet-360-font-2.0x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ActivityShot.Rows, ActivityShot.SheetShell).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** A right-to-left UI (dark): the row mirrors, the argument stays left to right. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ActivityRtlScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapActivity(ActivityShot.Rows, skin, "activity-rows-rtl", "phone", WellHeightPhone, layoutDirection = LayoutDirection.Rtl)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(TetherSkin.StudioDark))
    }
}
