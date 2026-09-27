package com.tether.app.gallery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeChoice
import com.tether.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T3.4: the gallery's own sections as goldens, every skin at phone size (412×915dp @420dpi, the
 * web's 412×915 @2.625). Only what T3.3's primitive goldens (:core:designsystem) do not already
 * cover: the type roles, the 136-glyph icon grid, the provider logos and the adaptive launcher icon.
 * `recordRoborazziDebug` writes src/testDebug/screenshots/gallery; `verifyRoborazziDebug` (the gate)
 * fails on any changed pixel.
 *
 * Robolectric SDK 34 (the project JDK is 17; newer sandboxes need Java 21, as in the other app tests).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class GalleryScreenshotTest(private val golden: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun section() {
        val (title, content) = GalleryGoldens.getValue(golden)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TetherTheme(ThemeChoice(skin.family, if (skin.isDark) ThemeMode.Dark else ThemeMode.Light)) {
                GalleryBoard(title, Modifier.fillMaxWidth().testTag(Tag), content)
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(Tag).captureRoboImage(
            "src/testDebug/screenshots/gallery/$golden/${skin.id}-phone.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
        )
    }

    companion object {
        private const val Tag = "gallery-section"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = GalleryGoldens.keys.flatMap { g -> TetherSkin.entries.map { arrayOf<Any>(g, it) } }
    }
}
