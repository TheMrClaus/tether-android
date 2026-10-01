package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
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
 * ta-fz3 visual states of the external-link confirm sheet: `external-link-confirm` = an
 * international host (Cyrillic a in a Latin name) shown as punycode with its note, a port on its
 * own row and a percent-escaped RLO left encoded in the path; `external-link-confirm-rtl` = the
 * sheet in an RTL UI with a Hebrew path (r2: shown, and opened, percent-encoded as UTF-8), laid
 * out left to right; `external-link-mail` = a mailto link with its recipient (r2: the query is
 * dropped, so the Address row is the address alone).
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

internal const val SHOT_INTERNATIONAL = "https://ex\u0430mple.com:8443/docs/%E2%80%AE?q=1"
internal const val SHOT_RTL_PATH = "https://example.com/\u05E9\u05DC\u05D5\u05DD/\u05E2\u05D5\u05DC\u05DD/?x=1"
internal const val SHOT_MAIL = "mailto:ops@example.test?subject=Deploy%20report"

private fun AndroidComposeTestRule<*, ComponentActivity>.snapLinkConfirm(skin: TetherSkin, href: String, name: String, rtl: Boolean = false) {
    mainClock.autoAdvance = false
    val target = checkNotNull(SafeHref.target(href))
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(
                LocalReducedMotion provides true,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                ExternalLinkConfirmDialog(target = target, identity = 1L, onConfirm = {}, onCancel = {})
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ExternalLinkConfirmScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapLinkConfirm(skin, SHOT_INTERNATIONAL, "external-link-confirm")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: 1.3x font scale (Studio light + dark); the RTL UI and the mail variant. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ExternalLinkConfirmFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirm() = rule.snapLinkConfirm(skin, SHOT_INTERNATIONAL, "external-link-confirm-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ExternalLinkVariantScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun rtlUi() = rule.snapLinkConfirm(skin, SHOT_RTL_PATH, "external-link-confirm-rtl", rtl = true)

    @Test fun mail() = rule.snapLinkConfirm(skin, SHOT_MAIL, "external-link-mail")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
