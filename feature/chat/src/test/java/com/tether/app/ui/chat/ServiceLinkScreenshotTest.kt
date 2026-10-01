package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.ui.text.SafeHref
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T15.7 visual states of a worktree service's "Open" confirm sheet, Studio light and dark only
 * (T15.5: Studio is the app's one appearance): `service-link-confirm` = a service hostname and the
 * console's pinned worktree-open link; `service-link-confirm-hostile` = a server-named host with an
 * RLO, a line feed and a zero-width space, each drawn as a visible token on one line.
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private const val SHOT_SERVICE_LINK = "https://console.example.test:443/api/worktree/open?session=s1&script=web"

private fun AndroidComposeTestRule<*, ComponentActivity>.snapServiceConfirm(skin: TetherSkin, service: String, name: String) {
    mainClock.autoAdvance = false
    val target = checkNotNull(SafeHref.target(SHOT_SERVICE_LINK))
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ServiceLinkConfirmDialog(service = service, target = target, identity = 1L, onConfirm = {}, onCancel = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ServiceLinkScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapServiceConfirm(skin, "web--feat--tether-1a2b3c.svc.example.test", "service-link-confirm")

    @Test fun hostileHost() = rule.snapServiceConfirm(skin, "web\u202Egnp.evil.example\nforged\u200B", "service-link-confirm-hostile")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
