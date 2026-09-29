package com.tether.app.ui.sidebar

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T13.2 (SYNC_DESIGN §4.2, native-only: no web reference): the sidebar offline. Every row's status
 * is from a saved list, so running / waiting read "Was …" on a faint still dot, and each row carries
 * its copy's glyph: `history` + a short age, or `cloud-off` when nothing is on the device.
 */
object SidebarSyncFixtures {
    private val F = SidebarFixtures
    private fun ago(min: Long) = F.NOW - min * 60_000

    val syncStates = mapOf(
        "a1" to SessionSync(Freshness.Saved, ago(12)),
        "a2" to SessionSync(Freshness.Saved, ago(3 * 60)),
        "a3" to SessionSync(Freshness.NotDownloaded, null),
        "a4" to SessionSync(Freshness.Saved, ago(2 * 24 * 60)),
    )

    fun offline(connected: Boolean = false): SidebarState =
        F.state(F.statusSessions, histories = F.statusHistories, connected = connected).copy(syncStates = syncStates)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SidebarSyncPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSyncSidebar(skin, "phone", TetherLayoutClass.Phone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 1.3f)
class SidebarSyncTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sidebar() = rule.snapSyncSidebar(skin, "tablet", TetherLayoutClass.Expanded)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapSyncSidebar(skin: TetherSkin, size: String, layout: TetherLayoutClass) {
    mainClock.autoAdvance = false
    setContent { SidebarUnderTest(skin, SidebarSyncFixtures.offline(), layout, SidebarUiSeed(), SidebarActions(onCollapse = {})) }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    com.github.takahirom.roborazzi.captureScreenRoboImage(
        "src/test/screenshots/sidebar-sync-offline-font-1.3x/${skin.id}-$size.png",
        roborazziOptions = com.github.takahirom.roborazzi.RoborazziOptions(
            compareOptions = com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions(changeThreshold = 0f),
        ),
    )
}

/** What TalkBack reads for an offline row, and that a live list shows none of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SidebarSyncTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun offlineRowsSayWasAndHowOldTheirCopyIs() {
        rule.setContent { SidebarUnderTest(TetherSkin.Machine, SidebarSyncFixtures.offline(), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNodeWithContentDescription("Refactor the retry loop, chat, Was running", substring = true).assertExists()
        rule.onNodeWithContentDescription("Saved copy · updated 12 min ago", substring = true).assertExists()
        rule.onNodeWithContentDescription("Approve the lint fix, chat, Was waiting on you", substring = true).assertExists()
        rule.onNodeWithContentDescription("Not downloaded. Connect to load", substring = true).assertExists()
        rule.onNodeWithTag(SidebarTags.freshness("live:a1"), useUnmergedTree = true).assertExists()
        // Offline, nothing reads as live now.
        rule.onNodeWithContentDescription("chat, Active", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("chat, Needs you", substring = true).assertDoesNotExist()
        // ...and the visible words say so too (not only the TalkBack sentence).
        rule.onNodeWithText("Was running", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Was waiting on you", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Active", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Needs you", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aLiveListShowsNoGlyphAndTheOrdinaryStatus() {
        rule.setContent { SidebarUnderTest(TetherSkin.Machine, SidebarSyncFixtures.offline(connected = true), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNodeWithTag(SidebarTags.freshness("live:a1"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Refactor the retry loop, chat, Active", substring = true).assertExists()
        rule.onNodeWithContentDescription("Saved copy", substring = true).assertDoesNotExist()
    }
}
