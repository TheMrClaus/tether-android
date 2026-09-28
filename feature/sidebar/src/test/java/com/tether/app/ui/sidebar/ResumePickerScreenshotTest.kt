package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.2: the resume picker's states. The web has no separate picker: discovered conversations are
 * the sidebar's history-only rows (session-sidebar.tsx), so these render [SessionSidebar] in its
 * hosts like the T5.1 goldens. `list` = a block of resumable history rows beside one live chat
 * (the selected one); `opening` = a history row just tapped: it is the selected row while the
 * server creates its session (dashboard.tsx openingHistoryId), and the live chat is let go.
 */
enum class ResumeShot(val id: String) {
    List("list"),
    Opening("opening"),
}

private val F = SidebarFixtures

private val liveChat = F.live("r1", "Wire the resume picker", status = "ready", ago = 3, historyId = "h-live")

private val resumeHistories = mapOf(
    F.ROOT to listOf(
        F.history("h-live", "Wire the resume picker", ago = 3, seenAgo = 0),
        F.history(
            "h-unread", "Nightly dependency bump", ago = 45, seenAgo = 90,
            digest = HistoryDigest(2, "Bumped okhttp and compose; the build is green."),
        ),
        F.history("h-codex", "Codex: tidy the release notes", provider = "codex", ago = 5 * 60, seenAgo = 4 * 60),
        F.history("h-opencode", "Opencode: sketch the sync outbox", provider = "opencode", ago = 26 * 60, seenAgo = 25 * 60),
        F.history("h-old", "Profile-pinned review thread", ago = 3 * 24 * 60, seenAgo = 3 * 24 * 60).copy(profileId = "work"),
    ),
)

fun resumeState(shot: ResumeShot): SidebarState = when (shot) {
    ResumeShot.List -> F.state(listOf(liveChat), histories = resumeHistories, activeId = liveChat.id)
    // dashboard.tsx:400-404: the selection is cleared and the tapped row is the opening one.
    ResumeShot.Opening -> F.state(listOf(liveChat), histories = resumeHistories, activeId = null, openingHistoryId = "h-codex")
}

private const val CaptureAtMs = 600L

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapResume(
    shot: ResumeShot,
    skin: TetherSkin,
    name: String,
    size: String,
    layout: TetherLayoutClass,
) {
    mainClock.autoAdvance = false
    setContent { SidebarUnderTest(skin, resumeState(shot), layout, SidebarUiSeed(), SidebarActions(onCollapse = {})) }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    captureScreenRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Both states × all 6 skins at the web's phone viewport (412×915 @2.625), in the drawer. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ResumePickerPhoneScreenshotTest(private val shot: ResumeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun picker() = rule.snapResume(shot, skin, "resume-${shot.id}", "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ResumeShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The expanded layout's rail column (tablet 1280×800). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ResumePickerTabletScreenshotTest(private val shot: ResumeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun picker() = rule.snapResume(shot, skin, "resume-${shot.id}", "tablet", TetherLayoutClass.Expanded)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ResumeShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument uppercase + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ResumePickerFontScaleScreenshotTest(private val shot: ResumeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun picker() = rule.snapResume(shot, skin, "resume-${shot.id}-font-1.3x", "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ResumeShot.entries.flatMap { s ->
            listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
