package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * T15.4: the redesigned top bar (components/topbar.tsx; the approved Studio concept,
 * design/mockups/tether-overview/) in Studio light and dark, on its own over the workspace floor:
 * `bar` = the bar with Sessions current and a secure link; `menu` = the utility menu open on the
 * Overview with three unseen warnings and no session (Files unavailable, with its reason), the
 * link reconnecting. Scheduled, Usage and Accounts are shown unavailable (T9.3 / T9.2).
 */
enum class TopbarShot(val id: String, val current: TopBarDestination, val menuOpen: Boolean, val link: LinkReadout, val warnings: Int, val session: Boolean) {
    Bar("bar", TopBarDestination.Sessions, false, LinkReadout.Connected, 0, true),
    Menu("menu", TopBarDestination.Overview, true, LinkReadout.Reconnecting, 3, false),
}

private val StudioSkins = listOf(TetherSkin.Studio, TetherSkin.StudioDark)

private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapTopbar(shot: TopbarShot, skin: TetherSkin, wide: Boolean, size: String) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                val t = LocalTetherTokens.current
                BoxWithConstraints(Modifier.fillMaxSize().background(t.graphite)) {
                    val state = TopbarState(
                        current = shot.current,
                        link = shot.link,
                        wide = wide,
                        drawerKey = !wide && shot.current == TopBarDestination.Sessions,
                        menuOpen = shot.menuOpen,
                        viewportWidth = maxWidth.value.roundToInt(),
                        unseenWarnings = shot.warnings,
                        fileBrowserDisabled = !shot.session,
                    )
                    val actions = TopbarActions(
                        onOpenDrawer = {},
                        onOpenFiles = {},
                        onOpenLog = {},
                        onLogout = {},
                        onOpenSettings = {},
                        onNavigate = {},
                    )
                    val fold = androidx.compose.runtime.remember { TopbarFold() }
                    Box(Modifier.fillMaxSize()) {
                        TetherTopbar(actions, state, onToggleMenu = {}, fold = fold)
                        if (shot.menuOpen) TopbarMenu(actions, state, onDismiss = {}, fold = fold)
                    }
                }
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/topbar-${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

private fun topbarParams(): List<Array<Any>> = TopbarShot.entries.flatMap { s -> StudioSkins.map { arrayOf<Any>(s, it) } }

/** The web's phone viewport (412×915 @2.625): the narrow bar, navigation in the menu. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TopbarPhoneScreenshotTest(private val shot: TopbarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun topbar() = rule.snapTopbar(shot, skin, wide = false, size = "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = topbarParams()
    }
}

/** The web's tablet viewport (1280×800 @1x): destinations, Files and Accounts on the bar. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TopbarTabletScreenshotTest(private val shot: TopbarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun topbar() = rule.snapTopbar(shot, skin, wide = true, size = "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = topbarParams()
    }
}

/** Just above the 840dp cutoff (900dp): the expanded bar with Files and Accounts folded into the menu. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h700dp-mdpi")
class TopbarFoldableScreenshotTest(private val shot: TopbarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun topbar() = rule.snapTopbar(shot, skin, wide = true, size = "foldable")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = topbarParams()
    }
}

/** PLAN §4: 1.3× font scale does not break the narrow bar or its menu. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class TopbarFontScaleScreenshotTest(private val shot: TopbarShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun topbar() = rule.snapTopbar(shot, skin, wide = false, size = "phone-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = TopbarShot.entries.map { arrayOf<Any>(it, TetherSkin.Studio) }
    }
}
