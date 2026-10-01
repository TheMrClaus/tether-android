package com.tether.app.ui.sidebar

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.NewSessionGuard
import com.tether.app.ui.NEW_SESSION_NOT_OFFERED_COPY
import com.tether.app.ui.NewSessionPickerPreview
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-895: the New session picker's states, Studio light and dark, at the web's phone viewport.
 * `catalog` = the owner's case: two Claude accounts and an ACP profile first, then the default rows
 * (one ready, one whose model list failed, one loading, one unavailable); `pending` = this
 * connection's catalog not in yet, the base providers standing in; `not-offered` = a tapped row the
 * refreshed catalog dropped (nothing created, the picker says so).
 */
enum class NewSessionShot(val id: String) {
    Catalog("new-session-catalog"),
    Pending("new-session-pending"),
    NotOffered("new-session-not-offered"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun AndroidComposeTestRule<*, ComponentActivity>.snapPicker(shot: NewSessionShot, skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    val live = shot != NewSessionShot.Pending
    val rows = NewSessionGuard.rows(if (live) NewSessionFixtures.catalog else null, NewSessionFixtures.providers)
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                NewSessionPickerPreview(
                    rows = rows,
                    providers = NewSessionFixtures.providers,
                    catalogPending = !live,
                    notice = if (shot == NewSessionShot.NotOffered) NEW_SESSION_NOT_OFFERED_COPY else null,
                )
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NewSessionPickerScreenshotTest(private val shot: NewSessionShot, private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun picker() = rule.snapPicker(shot, skin, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = NewSessionShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class NewSessionPickerFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun picker() = rule.snapPicker(NewSessionShot.Catalog, skin, "new-session-catalog-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
