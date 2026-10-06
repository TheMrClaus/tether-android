package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.58 (#242): a prose picture in an assistant reply, loaded (`prose-image`) and failed
 * (`prose-image-failed`: "alt (image unavailable)"), Studio light and dark at phone size. The loaded fixture
 * is a 1280x800 screenshot (r2: a picture is laid out from the source's natural size, so a wide
 * shot fills the column), in a well tall enough that nothing is clipped.
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private const val REPLY = "The build page after the fix:\n\n![build page](/tmp/shots/build.png)\n\nBoth checks pass; the badge ![ci](https://ci.example.test/badge.png) sits inline with the text."

private fun AndroidComposeTestRule<*, ComponentActivity>.snap(skin: TetherSkin, loader: ToolMediaLoader, tag: String, name: String) {
    mainClock.autoAdvance = false
    setContent {
        ChatHost(skin, wellHeight = 780.dp) {
            CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                Column(Modifier.padding(12.dp)) {
                    MarkdownBody(parseMarkdown(REPLY), LocalTetherTypography.current.chatBody, com.tether.app.ui.theme.LocalTetherTokens.current.ink)
                }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProseImageScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun loaded() = rule.snap(skin, ToolFixtures.FakeLoader(MediaImage.Ok(ToolFixtures.checker(1280, 800, tile = 80))), "md-image", "prose-image")

    @Test fun failed() = rule.snap(skin, ToolFixtures.FakeLoader(MediaImage.Failed), "md-image-missing", "prose-image-failed")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
