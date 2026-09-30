package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sidebar's states. `drawer` is the web's `session-drawer` scenario (compared in
 * docs/parity/screens/sidebar); the others have no web shot and are reviewed per skin.
 */
enum class SidebarShot(val id: String) {
    Drawer("drawer"),
    Empty("empty"),
    Groups("groups"),
    Archived("archived"),
    Status("status"),
    RowActions("row-actions"),
    SortMenu("sort-menu"),
    HarnessMenu("harness-menu"),
    Drag("drag"),
    Unread("unread-lens"),
}

private val F = SidebarFixtures

fun sidebarState(shot: SidebarShot): SidebarState = when (shot) {
    SidebarShot.Drawer, SidebarShot.SortMenu, SidebarShot.HarnessMenu -> F.state(F.drawerSessions)
    SidebarShot.Empty -> F.state(emptyList())
    SidebarShot.Groups -> F.state(
        F.groupSessions,
        pinned = listOf(F.DOCS, F.APP),
        current = F.APP,
        activeId = "g3",
        orders = mapOf(F.APP to listOf("live:g3")),
        collapsed = listOf(F.DOCS),
    )
    SidebarShot.Archived -> F.state(F.groupSessions, pinned = listOf(F.DOCS, F.APP), current = F.APP, collapsed = listOf(F.DOCS, F.APP))
    SidebarShot.Status, SidebarShot.RowActions -> F.state(
        F.statusSessions,
        histories = F.statusHistories,
        activeId = "a1",
    )
    SidebarShot.Drag -> F.state(F.drawerSessions.take(5))
    SidebarShot.Unread -> F.state(F.statusSessions, histories = F.statusHistories, unreadOnly = true)
}

fun sidebarSeed(shot: SidebarShot): SidebarUiSeed = when (shot) {
    SidebarShot.RowActions -> SidebarUiSeed(armedKey = "live:a2", swipedKey = "live:a4")
    SidebarShot.SortMenu -> SidebarUiSeed(sortMenuOpen = true)
    SidebarShot.HarnessMenu -> SidebarUiSeed(harnessMenuOpen = true)
    SidebarShot.Drag -> SidebarUiSeed(drag = DragPreview("live:s04", listOf("live:s01", "live:s04", "live:s02", "live:s03", "live:s05")))
    SidebarShot.Groups -> SidebarUiSeed(openChildren = setOf("live:g3"))
    SidebarShot.Archived -> SidebarUiSeed(archivedOpen = true)
    else -> SidebarUiSeed()
}

/** 600ms past the first frame: every transition has settled. */
private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapSidebar(shot: SidebarShot, skin: TetherSkin, name: String, size: String, layout: TetherLayoutClass) {
    mainClock.autoAdvance = false
    // The real host wires every callback; Collapse (desktop only) must be present to be drawn.
    setContent { SidebarUnderTest(skin, sidebarState(shot), layout, sidebarSeed(shot), SidebarActions(onCollapse = {})) }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    // The screen capture includes the menus' popup windows.
    captureScreenRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every state × all 6 skins at the web's phone viewport (412×915 @2.625), in the drawer. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarPhoneScreenshotTest(private val shot: SidebarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(shot, skin, "sidebar-${shot.id}", "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SidebarShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The expanded layout's rail column (tablet 1280×800): no mobile header, desktop row metrics, collapse key. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SidebarTabletScreenshotTest(private val shot: SidebarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(shot, skin, "sidebar-${shot.id}", "tablet", TetherLayoutClass.Expanded)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SidebarShot.Drawer, SidebarShot.Groups).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the rows (instrument uppercase + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SidebarFontScaleScreenshotTest(private val shot: SidebarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(shot, skin, "sidebar-${shot.id}-font-1.3x", "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SidebarShot.Drawer, SidebarShot.Status).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
