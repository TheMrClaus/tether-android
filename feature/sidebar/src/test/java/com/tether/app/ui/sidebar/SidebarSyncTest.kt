package com.tether.app.ui.sidebar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarSyncFixtures.offline(), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
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
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarSyncFixtures.offline(connected = true), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNodeWithTag(SidebarTags.freshness("live:a1"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Refactor the retry loop, chat, Active", substring = true).assertExists()
        rule.onNodeWithContentDescription("Saved copy", substring = true).assertDoesNotExist()
    }

    // ---- r2 ----------------------------------------------------------------------------------------

    @Test
    fun offlineARowWithNoFreshnessEntryStillNeverSaysItNeedsYou() {
        // Item 3: the "was" words and the still dot follow the link, not the entry.
        val bare = SidebarSyncFixtures.offline().copy(syncStates = emptyMap())
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, bare, TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNodeWithContentDescription("Refactor the retry loop, chat, Was running", substring = true).assertExists()
        rule.onNodeWithContentDescription("Approve the lint fix, chat, Was waiting on you", substring = true).assertExists()
        rule.onNodeWithText("Was running", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Was waiting on you", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Needs you", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Active", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("chat, Needs you", substring = true).assertDoesNotExist()
        // No entry: no glyph, no age, and no sentence claiming one.
        rule.onNodeWithTag(SidebarTags.freshness("live:a1"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Saved copy", substring = true).assertDoesNotExist()
    }

    private fun stateContains(text: String) = SemanticsMatcher("stateDescription contains '$text'") {
        it.config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
    }

    @Test
    fun offlineTheWorkspaceHeaderSaysWasOnAStillDot() {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarSyncFixtures.offline().copy(syncStates = emptyMap()), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNode(stateContains(", 1 was waiting")).assertExists()
        rule.onAllNodes(stateContains(", 1 waiting")).fetchSemanticsNodes().let { assertTrue("offline: no '1 waiting' now", it.isEmpty()) }
        rule.onNodeWithTag(SidebarTags.blockDot(SidebarFixtures.ROOT), useUnmergedTree = true).assertExists()
    }

    @Test
    fun aLiveListsWorkspaceHeaderKeepsItsLiveCountAndDot() {
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarSyncFixtures.offline(connected = true), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions()) }
        rule.onNode(stateContains(", 1 waiting")).assertExists()
        rule.onNodeWithTag(SidebarTags.blockDot(SidebarFixtures.ROOT), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun offlineARowsEndControlIsInertAndItsSwipeIsGone() {
        val ended = mutableListOf<String>()
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, SidebarSyncFixtures.offline(), TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions(onEndSession = { id, _ -> ended += id })) }
        rule.onNodeWithTag(SidebarTags.end("live:a1")).assertIsNotEnabled().performClick()
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertTrue("a saved list never ends a session: $ended", ended.isEmpty())
        rule.onNodeWithContentDescription("End Refactor the retry loop, unavailable: connect to end it").assertExists()
        rule.onNodeWithTag(SidebarTags.archive("live:a1")).assertDoesNotExist()
    }

    @Test
    fun aLiveListsRowStillEndsWithItsTwoTaps() {
        val ended = mutableListOf<String>()
        val state = SidebarSyncFixtures.offline(connected = true).copy(origin = ORIGIN_A)
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state, TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions(onEndSession = { id, o -> ended += "$id@$o" })) }
        rule.onNodeWithTag(SidebarTags.end("live:a1")).assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@$ORIGIN_A"), ended)
    }

    /**
     * r3: the second tap carries the server the control was ARMED for, not the one the list shows
     * by then: armed on A, tapped after a switch to B (where a same-id row is listed), the client is
     * asked to end it on A, so it refuses it. Re-armed on B, it carries B.
     */
    @Test
    fun anArmedRowCarriesTheOriginItWasArmedFor() {
        val ended = mutableListOf<String>()
        var state by mutableStateOf(SidebarSyncFixtures.offline(connected = true).copy(origin = ORIGIN_A))
        rule.setContent { SidebarUnderTest(TetherSkin.StudioDark, state, TetherLayoutClass.Phone, SidebarUiSeed(), SidebarActions(onEndSession = { id, o -> ended += "$id@$o" })) }
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        rule.runOnIdle { state = state.copy(origin = ORIGIN_B) }
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@$ORIGIN_A"), ended)

        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.end("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@$ORIGIN_A", "a1@$ORIGIN_B"), ended)
    }

    /** r3: the swipe is step one of the end: it carries the server it was opened on. */
    @Test
    fun aSwipedRowCarriesTheOriginItWasOpenedFor() {
        val ended = mutableListOf<String>()
        var state by mutableStateOf(SidebarSyncFixtures.offline(connected = true).copy(origin = ORIGIN_A))
        rule.setContent {
            SidebarUnderTest(TetherSkin.StudioDark, state, TetherLayoutClass.Phone, SidebarUiSeed(swipedKey = "live:a1"), SidebarActions(onEndSession = { id, o -> ended += "$id@$o" }))
        }
        rule.runOnIdle { state = state.copy(origin = ORIGIN_B) }
        rule.waitForIdle()
        rule.onNodeWithTag(SidebarTags.archive("live:a1")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("a1@$ORIGIN_A"), ended)
    }

    private companion object {
        const val ORIGIN_A = "https://a.example"
        const val ORIGIN_B = "https://b.example"
    }
}
