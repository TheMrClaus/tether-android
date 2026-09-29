package com.tether.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.LinkBanner
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val NOW = 1_800_000_000_000L
private const val VERIFIED = NOW - 12 * 60_000L

/** A running session shown from a saved copy while the link is down (T13.2, no web reference). */
val offlineSession = ShellFixtures.idle.copy(status = "active")

fun offlineFreshness(sync: SessionSync = SessionSync(Freshness.Saved, VERIFIED)) = ShellFreshness(
    banner = LinkBanner.Offline,
    syncStates = mapOf(offlineSession.id to sync),
    listLive = false,
    now = NOW,
)

@Composable
private fun WithFreshness(freshness: ShellFreshness, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShellFreshness provides freshness, content = content)
}

private fun ComposeContentTestRule.snapSync(expanded: Boolean, skin: TetherSkin) {
    mainClock.autoAdvance = false
    setContent {
        WithFreshness(offlineFreshness()) {
            if (expanded) ExpandedShellUnderTest(skin, PhoneShellState(), offlineSession) else ShellUnderTest(skin, PhoneShellState(), offlineSession)
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/shell-sync-offline-font-1.3x/${skin.id}-${if (expanded) "tablet" else "phone"}.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** The banner, the header's freshness chip and its qualified status pill, phone, 1.3× font, 6 skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ShellSyncPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapSync(expanded = false, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** The same in the expanded shell (tablet, 1.3× font, 6 skins). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 1.3f)
class ShellSyncExpandedScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun shell() = rule.snapSync(expanded = true, skin)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** T13.2: what TalkBack hears from the shell's freshness marks, and when they show. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ShellFreshnessTest {
    @get:Rule val rule = createComposeRule()

    private fun description(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun theBannerFollowsTheLink() {
        assertNull(ShellFreshness.bannerFor(ConnectionState.Connected))
        assertEquals(LinkBanner.Reconnecting, ShellFreshness.bannerFor(ConnectionState.Connecting))
        assertEquals(LinkBanner.Offline, ShellFreshness.bannerFor(ConnectionState.Disconnected))
        assertEquals(LinkBanner.Offline, ShellFreshness.bannerFor(ConnectionState.LocalNetworkBlocked))
        assertNull(ShellFreshness.bannerFor(ConnectionState.AuthRequired))
    }

    @Test
    fun offlineTheHeaderSaysWasRunningAndTheChipSaysHowOldTheCopyIs() {
        rule.setContent { WithFreshness(offlineFreshness()) { ShellUnderTest(TetherSkin.Machine, PhoneShellState(), offlineSession) } }
        rule.onNodeWithTag(ShellTags.LinkBanner).assert(description("Offline. Showing saved copies"))
        rule.onNodeWithTag(ShellTags.FreshnessChip).assert(description("Saved copy · updated 12 min ago"))
        rule.onNodeWithTag(ShellTags.StatusPill).assert(description("Was running"))
    }

    @Test
    fun aWaitingSessionFromASavedCopyNeverSaysItNeedsYouNow() {
        val waiting = offlineSession.copy(status = "waiting")
        rule.setContent { WithFreshness(offlineFreshness()) { ShellUnderTest(TetherSkin.Machine, PhoneShellState(), waiting) } }
        rule.onNodeWithTag(ShellTags.StatusPill).assert(description("Was waiting on you"))
    }

    @Test
    fun liveShowsNoMarkAndTheOrdinaryStatus() {
        val live = ShellFreshness(syncStates = mapOf(offlineSession.id to SessionSync(Freshness.Live, NOW)), listLive = true, now = NOW)
        rule.setContent { WithFreshness(live) { ShellUnderTest(TetherSkin.Machine, PhoneShellState(), offlineSession) } }
        rule.onNodeWithTag(ShellTags.LinkBanner).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.FreshnessChip).assertDoesNotExist()
        rule.onNodeWithTag(ShellTags.StatusPill).assert(description("Active"))
    }

    @Test
    fun catchingUpOnALiveLinkMarksTheCopyButNotTheStatus() {
        val catching = ShellFreshness(syncStates = mapOf(offlineSession.id to SessionSync(Freshness.CatchingUp, VERIFIED)), listLive = true, now = NOW)
        rule.setContent { WithFreshness(catching) { ShellUnderTest(TetherSkin.Machine, PhoneShellState(), offlineSession) } }
        rule.onNodeWithTag(ShellTags.FreshnessChip).assert(description("Catching up…"))
        rule.onNodeWithTag(ShellTags.StatusPill).assert(description("Active"))
    }
}
