package com.tether.app.ui.components.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.ConnectionBanner
import com.tether.app.ui.components.FreshnessChip
import com.tether.app.ui.components.FreshnessCopy
import com.tether.app.ui.components.FreshnessGlyph
import com.tether.app.ui.components.FreshnessPill
import com.tether.app.ui.components.LinkBanner
import com.tether.app.ui.components.TetherStatusPill
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** A fixed clock for the boards: the saved copy was verified 12 minutes before it. */
const val BOARD_NOW: Long = 1_800_000_000_000L
const val BOARD_VERIFIED: Long = BOARD_NOW - 12 * 60_000L

/**
 * T13.2: every freshness mark on one board (native-only: no web reference). Captured at 1.3×
 * font, the scale the marks must survive, in all both Studio skins at phone and tablet sizes.
 */
@Composable
fun FreshnessBoard() {
    val saved = SessionSync(Freshness.Saved, BOARD_VERIFIED)
    StateRow("banner") { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { ConnectionBanner(LinkBanner.Offline); ConnectionBanner(LinkBanner.Reconnecting) } }
    StateRow("session chip", wrap = true) {
        FreshnessChip(SessionSync(Freshness.CatchingUp, null), BOARD_NOW)
        FreshnessChip(saved, BOARD_NOW)
        FreshnessChip(SessionSync(Freshness.NotDownloaded, null), BOARD_NOW)
    }
    StateRow("sidebar glyph") {
        FreshnessGlyph(saved, BOARD_NOW)
        FreshnessGlyph(SessionSync(Freshness.NotDownloaded, null), BOARD_NOW)
    }
    StateRow("qualified badges", wrap = true) {
        for (status in listOf("active", "waiting")) {
            val (label, tone) = FreshnessCopy.statusPill(status, listLive = false, verifiedAt = BOARD_VERIFIED, now = BOARD_NOW)
            TetherStatusPill(label, tone)
        }
    }
    StateRow("older turns") { FreshnessPill(TetherIcons.CloudOff, FreshnessCopy.OLDER_TURNS) }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class FreshnessPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapBoard("sync-indicators-font-1.3x", skin, ScreenSize.Phone, reducedMotion = true) { FreshnessBoard() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 1.3f)
class FreshnessTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapBoard("sync-indicators-font-1.3x", skin, ScreenSize.Tablet, reducedMotion = true) { FreshnessBoard() }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
