package com.tether.app.ui.overview

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.protocol.overview.OverviewClientState
import com.tether.app.ui.theme.TetherSkin
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * T15.2: the Overview's states in the two Studio skins (the Overview is Studio-only on the web,
 * OVERVIEW_STUDIO_PLAN.md §1) at the web's phone width (412dp @2.625), tall enough to show the
 * whole page in one frame (the phone order: header, filters, pending, cards, activity, footer):
 * `populated` = the approved concept's data (design/mockups/tether-overview), `empty` = a node
 * with no sessions, `offline` = the link dropped (stale, qualified statuses), `loading` =
 * subscribed with no snapshot yet. Clocks read UTC.
 */
enum class OverviewShot(val id: String, val state: OverviewClientState, val connected: Boolean) {
    Populated("populated", OverviewFixtures.populated, true),
    Empty("empty", OverviewFixtures.empty, true),
    Offline("offline", OverviewFixtures.offline, false),
    Loading("loading", OverviewFixtures.loading, true),
}

private const val CaptureAtMs = 600L

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2600dp-420dpi")
class OverviewPhoneScreenshotTest(private val shot: OverviewShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private val zone = TimeZone.getDefault()

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restore() = TimeZone.setDefault(zone)

    @Test fun overview() {
        rule.mainClock.autoAdvance = false
        rule.setContent { OverviewUnderTest(skin, shot.state, shot.connected) }
        rule.mainClock.advanceTimeBy(CaptureAtMs)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/overview-${shot.id}/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = OverviewShot.entries.flatMap { s -> listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the page (Studio light, the populated state). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h3200dp-420dpi", fontScale = 1.3f)
class OverviewFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    private val zone = TimeZone.getDefault()

    @Before fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun restore() = TimeZone.setDefault(zone)

    @Test fun overview() {
        rule.mainClock.autoAdvance = false
        rule.setContent { OverviewUnderTest(skin, OverviewFixtures.populated) }
        rule.mainClock.advanceTimeBy(CaptureAtMs)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/screenshots/overview-populated-font-1.3x/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(TetherSkin.Studio))
    }
}
