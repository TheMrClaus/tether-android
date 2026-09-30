package com.tether.app.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.HostMetrics
import com.tether.app.client.OverviewUsage
import com.tether.app.ui.overview.HostUsageFixtures.NOW
import com.tether.app.ui.overview.HostUsageFixtures.fresh
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.3: the "Host & usage" tile's states in the two Studio skins at the web's phone width (412dp,
 * the Overview's 14dp gutter): `populated` = the approved concept's readings
 * (design/mockups/tether-overview), `partial` = a stale reading with partial daily coverage,
 * `loading` = before the first answer, `unavailable` = the routes failed with nothing to keep,
 * `blocked` = a sign-in gateway answered, `offline` = the link is down over an old reading with
 * unavailable meters.
 */
enum class HostUsageShot(
    val id: String,
    val host: MetricsReading<HostMetrics>,
    val usage: MetricsReading<OverviewUsage>,
    val connected: Boolean = true,
) {
    Populated("populated", fresh(HostUsageFixtures.host), fresh(HostUsageFixtures.usageExact)),
    Partial("partial", fresh(HostUsageFixtures.host, at = NOW - 180_000), fresh(HostUsageFixtures.usagePartial, at = NOW - 120_000)),
    Loading("loading", MetricsReading(), MetricsReading()),
    Unavailable("unavailable", MetricsReading(fault = MetricsFault.Unavailable(500)), MetricsReading(fault = MetricsFault.Unavailable(null))),
    Blocked("blocked", MetricsReading(fault = MetricsFault.Blocked(302)), MetricsReading(fault = MetricsFault.Blocked(302))),
    Offline("offline", fresh(HostUsageFixtures.hostWarming, at = NOW - 720_000), fresh(HostUsageFixtures.usagePartial, at = NOW - 720_000), connected = false),
}

private const val ShotTag = "host-usage-shot"

@Composable
private fun HostUsageUnderTest(skin: TetherSkin, shot: HostUsageShot) {
    TetherTheme(choiceFor(skin)) {
        CompositionLocalProvider(LocalReducedMotion provides true) {
            Box(Modifier.fillMaxWidth().background(LocalTetherTokens.current.mineral).padding(14.dp).testTag(ShotTag)) {
                HostUsagePanel(HostUsagePresentation.tile(shot.host, shot.usage, NOW, shot.connected))
            }
        }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1200dp-420dpi")
class HostUsagePhoneScreenshotTest(private val shot: HostUsageShot, private val skin: TetherSkin) {
    @get:org.junit.Rule val rule = createComposeRule()

    @Test fun tile() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HostUsageUnderTest(skin, shot) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(ShotTag).captureRoboImage(
            "src/test/screenshots/host-usage-${shot.id}/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HostUsageShot.entries.flatMap { s -> listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) } }
    }
}

/** overview.module.css `@media (max-width: 22.5rem)`: on a 320dp screen the meters stack. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h1200dp-420dpi")
class HostUsageNarrowScreenshotTest(private val shot: HostUsageShot) {
    @get:org.junit.Rule val rule = createComposeRule()

    @Test fun tile() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HostUsageUnderTest(TetherSkin.Studio, shot) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(ShotTag).captureRoboImage(
            "src/test/screenshots/host-usage-${shot.id}/studio-narrow.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(HostUsageShot.Populated))
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h1200dp-420dpi", fontScale = 1.3f)
class HostUsageFontScaleScreenshotTest(private val shot: HostUsageShot) {
    @get:org.junit.Rule val rule = createComposeRule()

    @Test fun tile() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HostUsageUnderTest(TetherSkin.Studio, shot) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(ShotTag).captureRoboImage(
            "src/test/screenshots/host-usage-${shot.id}-font-1.3x/studio-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>(HostUsageShot.Partial))
    }
}
