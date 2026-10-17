package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.Handover
import com.tether.app.protocol.helpers.SidebarRowModel.RowMenuItemId
import com.tether.app.protocol.model.HistoryDigest
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val F = SidebarFixtures
private const val DAY = 24L * 60

/**
 * S3a (SL-spec-3 §c.1): the phone row model's new shots, in both Studio skins: the badge slot, "Handed over", workspace counts,
 * the name group, and every state of the row menu. Every menu shot is a `captureScreenRoboImage` capture: the menu is a Popup in its
 * own window, so a node capture would record the row without it.
 */
enum class RowShot(val id: String) {
    Badges("badges"),
    HandedOver("handed-over"),
    WorkspaceCounts("workspace-counts"),
    NameGroup("name-group"),
    NameGroupOpen("name-group-open"),
    RowMenu("row-menu"),
    RowMenuOpenRow("row-menu-open-row"),
    RowMenuHandoverDisabled("row-menu-handover-disabled"),
    RowMenuHandoverStop("row-menu-handover-stop"),
    RowMenuHistory("row-menu-history"),
    RowMenuArmed("row-menu-armed"),
    RowMenuOffline("row-menu-offline"),
    RowMenuFlip("row-menu-flip"),
}

private fun badgeSessions() = F.chain(3, "b", ago = 5, names = listOf("Probe the parser", "Plan the importer", "Ship the importer")) + listOf(
    F.live("n1", "Review the changelog", ago = 20, historyId = "h-n1"),
    F.live("n2", "Rename the config keys", ago = 30, historyId = "h-n2"),
    F.live("n3", "Port the importer tests", ago = 40, historyId = "h-n3"),
    F.live("n4", "Tidy the README", ago = 50, historyId = "h-n4"),
    F.live("n5", "Codex patch pass", provider = "codex", ago = 60, historyId = "h-n5"),
    F.live("n6", "Quiet session", ago = 70),
)

private fun badgeHistories() = mapOf(
    F.APP to listOf(
        F.history("h-n1", "Review the changelog", ago = 20, seenAgo = 90, digest = HistoryDigest(3, "Reordered the migration notes.")),
        F.history("h-n2", "Rename the config keys", ago = 30, seenAgo = 90, digest = HistoryDigest(1, "Found two stale keys.")),
        F.history("h-n3", "Port the importer tests", ago = 40, seenAgo = 400, digest = HistoryDigest(250, "Ported all of them.")),
        F.history("h-n4", "Tidy the README", ago = 50, seenAgo = 90),
        F.history("h-n5", "Codex patch pass", provider = "codex", ago = 60, seenAgo = 90, digest = HistoryDigest(0, "Applied the patch.")),
    ),
)

private fun menuSessions() = F.statusSessions.map { if (it.id == "a1" || it.id == "a3") it.copy(resumeCommand = "claude --resume 7d1f2c") else it }

private fun menuState(activeHandover: Handover.MenuState = Handover.MenuState.Enabled, connected: Boolean = true) =
    F.state(menuSessions(), histories = F.statusHistories, activeId = "a1", connected = connected).copy(activeHandover = activeHandover, origin = "https://a.example")

fun rowState(shot: RowShot): SidebarState = when (shot) {
    RowShot.Badges -> F.state(badgeSessions(), histories = badgeHistories(), current = F.APP, activeId = "b2")
    RowShot.HandedOver -> F.state(F.chain(3, "h", ago = 5, names = listOf("Probe the parser", "Plan the importer", "Ship the importer"), status = { if (it == 1) "waiting" else "ready" }), current = F.APP, activeId = "h2")
    RowShot.WorkspaceCounts -> F.state(
        F.groupSessions + F.live("d3", "Draft the changelog", cwd = F.DOCS, ago = 20 * DAY),
        pinned = listOf(F.DOCS, F.ROOT),
        current = F.APP,
        histories = mapOf(F.ROOT to listOf(F.history("h-run", "Background CLI run", ago = 3).copy(origin = "agent-cli-child"))),
    )
    RowShot.NameGroup, RowShot.NameGroupOpen -> F.state(
        listOf(F.live("m1", "Monsters 1", ago = 30), F.live("m2", "Monsters 2", ago = 20), F.live("m3", "Monsters 3", ago = 10), F.live("solo", "Scratch notes", ago = 40)),
        current = F.APP,
        activeId = "m3",
    )
    RowShot.RowMenuHandoverDisabled -> menuState(Handover.MenuState.Blocked("A handover is already under way."))
    RowShot.RowMenuHandoverStop -> menuState(Handover.MenuState.Stop("Writing the handover summary…"))
    RowShot.RowMenuOffline -> menuState(connected = false)
    RowShot.RowMenuFlip -> F.state(F.drawerSessions.take(9), activeId = "s01")
    else -> menuState()
}

fun rowSeed(shot: RowShot): SidebarUiSeed = when (shot) {
    RowShot.HandedOver -> SidebarUiSeed(openChains = setOf("live:h0"))
    RowShot.NameGroupOpen -> SidebarUiSeed(openChains = setOf("live:m1"))
    RowShot.RowMenu -> SidebarUiSeed(menuKey = "history:h-away")
    RowShot.RowMenuOpenRow, RowShot.RowMenuHandoverDisabled, RowShot.RowMenuHandoverStop -> SidebarUiSeed(menuKey = "live:a1")
    RowShot.RowMenuHistory -> SidebarUiSeed(menuKey = "history:h-old")
    RowShot.RowMenuArmed -> SidebarUiSeed(menuKey = "live:a2", armedKey = "live:a2")
    RowShot.RowMenuOffline -> SidebarUiSeed(menuKey = "live:a2")
    RowShot.RowMenuFlip -> SidebarUiSeed(menuKey = "live:s09")
    else -> SidebarUiSeed()
}

private fun ComposeContentTestRule.snapRow(shot: RowShot, skin: TetherSkin, name: String, size: String = "phone", rtl: Boolean = false) {
    snapContent(skin, name, size) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
            SidebarUnderTest(skin, rowState(shot), TetherLayoutClass.Phone, rowSeed(shot), SidebarActions(onCollapse = {}))
        }
    }
}

private fun params(vararg shots: RowShot): List<Array<Any>> = shots.flatMap { s -> listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) } }

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarRowModelScreenshotTest(private val shot: RowShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shot() = rule.snapRow(shot, skin, "sidebar-${shot.id}")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = params(*RowShot.entries.toTypedArray())
    }
}

/** The badge at 1.3x (inline) and the menu opened from a copy tap ("Copied path"), both skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SidebarBadgesFont13ScreenshotTest(private val shot: RowShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shot() = rule.snapRow(shot, skin, "sidebar-${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = params(RowShot.Badges)
    }
}

/** 2.0x: the badge drops under the meta line; the menu's labels wrap and it scrolls inside. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 2.0f)
class SidebarRowModelFont2ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun badges() = rule.snapRow(RowShot.Badges, skin, "sidebar-badges-font-2.0x")
    @Test fun menu() = rule.snapRow(RowShot.RowMenuOpenRow, skin, "sidebar-row-menu-font-2.0x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** The menu mirrored (RTL aligns the menu's start edge with the kebab's). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarRowMenuRtlScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun menu() = rule.snapRow(RowShot.RowMenu, skin, "sidebar-row-menu-rtl", rtl = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/** "Copied path": the copy item flipped (and a Check) for its 1.5 s, the menu still open. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarRowMenuCopiedScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun menu() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SidebarUnderTest(skin, rowState(RowShot.RowMenuOpenRow), TetherLayoutClass.Phone, rowSeed(RowShot.RowMenuOpenRow), SidebarActions(onCollapse = {})) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.menuItem(RowMenuItemId.CopyPath), useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        captureScreenRoboImage(
            "src/test/screenshots/sidebar-row-menu-copied/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

/**
 * Director A1: a 360 dp phone at 1.3x with a nested chain member and a delegate child both under badges. The badge stacks when the
 * copy column with it inline would measure under 120 dp: here the member's "99+ new turns" stacks and the child's short "New" stays inline.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 1.3f)
class SidebarBadges360Font13ScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun badges() {
        val sessions = F.chain(2, "z", ago = 5, names = listOf("Plan the importer", "Ship the importer")).map { if (it.id == "z0") it.copy(historyId = "h-z0") else it } +
            F.live("zc", "Index the fixtures", ago = 7, parent = "z1", historyId = "h-zc")
        val histories = mapOf(
            F.APP to listOf(
                F.history("h-z0", "Plan the importer", ago = 6, seenAgo = 400, digest = HistoryDigest(250, "Ported all of them.")),
                F.history("h-zc", "Index the fixtures", ago = 7, seenAgo = 90),
            ),
        )
        rule.snapContent(skin, "sidebar-badges-360-font-1.3x", "phone") {
            SidebarUnderTest(
                skin,
                F.state(sessions, histories = histories, current = F.APP, activeId = "z1"),
                TetherLayoutClass.Phone,
                SidebarUiSeed(openChains = setOf("live:z0"), openChildren = setOf("live:z1")),
                SidebarActions(onCollapse = {}),
            )
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
