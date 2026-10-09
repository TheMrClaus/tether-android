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

    /** tether #244: idle > 7 days folded into the collapsed "Older" row (closed, then open). */
    Older("older"),
    OlderOpen("older-open"),

    /** tether #244: the "Archive idle sessions" dialog, its preview and its settled summary. */
    ArchiveStale("archive-stale"),
    ArchiveStaleDone("archive-stale-done"),
}

private const val DAY_MIN = 24L * 60

private fun olderSessions() = listOf(
    SidebarFixtures.live("o1", "Fix the flaky retry test", ago = 20),
    SidebarFixtures.live("o2", "Draft the release notes", ago = 3 * DAY_MIN),
    SidebarFixtures.live("o3", "Spike the new importer", ago = 9 * DAY_MIN),
    SidebarFixtures.live("o4", "Rework the settings tabs", ago = 21 * DAY_MIN),
    SidebarFixtures.live("o5", "Old pairing session", ago = 45 * DAY_MIN),
    SidebarFixtures.live("o6", "Keep: pinned long ago", ago = 60 * DAY_MIN).copy(pinned = true),
)

private fun archiveStalePreview() = com.tether.app.client.ArchiveStaleReply(
    1,
    com.tether.app.protocol.ServerMessage.ArchiveStaleResult(
        mode = "preview", days = 30, eligible = 12, archived = 0, failed = 0, remaining = 12,
        skipped = com.tether.app.protocol.ArchiveStaleSkipped(pinned = 2, inFlight = 1, viewing = 1),
        retention = com.tether.app.protocol.ArchiveStaleRetention(cap = 100, retiredNow = 95, willBePruned = 7),
    ),
)

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
    SidebarShot.Older, SidebarShot.OlderOpen -> F.state(olderSessions())
    SidebarShot.ArchiveStale -> F.state(F.drawerSessions.take(5), activeId = "s02").copy(archiveStale = archiveStalePreview())
    SidebarShot.ArchiveStaleDone -> F.state(F.drawerSessions.take(5), activeId = "s02")
}

fun sidebarSeed(shot: SidebarShot): SidebarUiSeed = when (shot) {
    SidebarShot.RowActions -> SidebarUiSeed(armedKey = "live:a2", swipedKey = "live:a4")
    SidebarShot.SortMenu -> SidebarUiSeed(sortMenuOpen = true)
    SidebarShot.HarnessMenu -> SidebarUiSeed(harnessMenuOpen = true)
    SidebarShot.Drag -> SidebarUiSeed(drag = DragPreview("live:s04", listOf("live:s01", "live:s04", "live:s02", "live:s03", "live:s05")))
    SidebarShot.Groups -> SidebarUiSeed(openChildren = setOf("live:g3"))
    SidebarShot.Archived -> SidebarUiSeed(archivedOpen = true)
    SidebarShot.OlderOpen -> SidebarUiSeed(olderOpen = setOf(F.ROOT))
    SidebarShot.ArchiveStale -> SidebarUiSeed(archiveStale = ArchiveStaleState())
    SidebarShot.ArchiveStaleDone -> SidebarUiSeed(archiveStale = ArchiveStaleState(days = 30, archived = 10, failed = 2, done = true))
    else -> SidebarUiSeed()
}

/** 600ms past the first frame: every transition has settled. */
private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapSidebar(shot: SidebarShot, skin: TetherSkin, name: String, size: String, layout: TetherLayoutClass) {
    // The real host wires every callback; Collapse (desktop only) must be present to be drawn.
    snapContent(skin, name, size) { SidebarUnderTest(skin, sidebarState(shot), layout, sidebarSeed(shot), SidebarActions(onCollapse = {})) }
}

/** Draws [content], lets every transition settle and compares (or records) the golden at `src/test/screenshots/<name>/<skin>-<size>.png`. */
fun ComposeContentTestRule.snapContent(skin: TetherSkin, name: String, size: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent { content() }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    // The screen capture includes the menus' popup windows.
    captureScreenRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every state × both Studio skins at the web's phone viewport (412×915 @2.625), in the drawer. */
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
        fun params(): List<Array<Any>> = listOf(SidebarShot.Drawer, SidebarShot.Groups, SidebarShot.Older, SidebarShot.ArchiveStale).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the rows (Studio light + dark). */
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

/**
 * ta-z4c1: the Status board at twice the text. The status line and the footer shrink like CSS flex
 * items, so no line ends inside a word (the web: "Needs / you", "Private / runtime"). A synthetic
 * stress, labelled so; the web's 1.0 board is the reference.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SidebarFontScale2PhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(SidebarShot.Status, skin, "sidebar-status-font-2.0x", "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** The same at the rail (tablet 1280×800). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 2.0f)
class SidebarFontScale2TabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(SidebarShot.Status, skin, "sidebar-status-font-2.0x", "tablet", TetherLayoutClass.Expanded)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/**
 * ta-1jj7 (owner-directed design): the compact phone drawer on a short landscape phone (640 x 360 dp, the
 * Phone class): the New session row is pinned and the rest of the header scrolls with the list.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h360dp-xhdpi")
class SidebarDrawerLandscapeScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSidebar(SidebarShot.Drawer, TetherSkin.StudioDark, "sidebar-drawer-landscape", "phone", TetherLayoutClass.Phone)
}

/** The phone drawer in a right-to-left layout: rows, header and footer mirror through start and end. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarDrawerRtlScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapContent(TetherSkin.StudioDark, "sidebar-drawer-rtl", "phone") {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            SidebarUnderTest(TetherSkin.StudioDark, sidebarState(SidebarShot.Drawer), TetherLayoutClass.Phone, sidebarSeed(SidebarShot.Drawer), SidebarActions(onCollapse = {}))
        }
    }
}

private fun ComposeContentTestRule.snapLongNames(name: String) = snapContent(TetherSkin.StudioDark, name, "phone") {
    SidebarUnderTest(
        TetherSkin.StudioDark,
        SidebarFixtures.state(SidebarFixtures.longNameSessions, histories = SidebarFixtures.longNameHistories, activeId = "l3"),
        TetherLayoutClass.Phone,
        SidebarUiSeed(),
        SidebarActions(onCollapse = {}),
    )
}

/** A 360 x 640 phone with names that do not fit one line: a long name, an overlong word, a worktree location, an unseen digest, a handoff. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class SidebarLongNames360ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapLongNames("sidebar-long-names-360")
}

/** The same at twice the text: rows grow, nothing clips, the header scrolls with the list. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xhdpi", fontScale = 2.0f)
class SidebarLongNames360Font2ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapLongNames("sidebar-long-names-360-font-2.0x")
}
