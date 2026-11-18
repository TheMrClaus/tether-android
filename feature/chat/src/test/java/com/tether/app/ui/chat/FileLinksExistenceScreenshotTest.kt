package com.tether.app.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** One relative mention the server has (a link) and one it does not (plain text), side by side. */
private const val EXISTENCE_REPLY = "Edited `src/there.kt`; `src/gone.kt` is not in the folder."

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapExistence(skin: TetherSkin, size: String, tablet: Boolean) {
    mainClock.autoAdvance = false
    val existence = answeredExistence(FakeExistsFiles(setOf("/w/p/src/there.kt")), "/w/p/src/there.kt", "/w/p/src/gone.kt")
    showFileLinks(
        FileLinkFixtures.opener(mutableListOf()), reply = EXISTENCE_REPLY, skin = skin,
        wellHeight = if (tablet) WellHeightTablet else WellHeightPhone, wellWidth = if (tablet) WellWidthTablet else null,
        existence = existence,
    )
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/file-links-existence/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class FileLinksExistencePhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapExistence(skin, "phone", tablet = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class FileLinksExistenceTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun board() = rule.snapExistence(skin, "tablet", tablet = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
