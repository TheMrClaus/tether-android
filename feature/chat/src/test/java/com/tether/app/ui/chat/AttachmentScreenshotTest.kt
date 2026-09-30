package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tether.app.client.AttachmentSendResult
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T7.4 visual states, every skin at phone size (the brief's minimum is Studio light and dark):
 * `attach-sheet` = the paperclip's sheet over a composer holding two staged attachments (the web's
 * touch shell: Add image, Paste image, Upload file); `composer-attachments` = those staged chips (a
 * picture with its thumbnail, a file with its glyph, names and sizes, the remove keys);
 * `bubble-attachments` = a sent message with a materialized picture (the thumbnail through the
 * tool-media path) and a file chip. Tablet (from 48rem): the sheet as the centred card. The failure
 * tile: `bubble-attachments-failed` (Studio light and dark).
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private val stagedFixture: List<StagedAttachment> by lazy {
    listOf(
        StagedAttachment(1, Attachment("screenshot.png", "image/png", ComposerAttachmentFixtures.PNG_BASE64), 245_760),
        StagedAttachment(2, Attachment("build-output.log", "text/plain", "aGVsbG8="), 18_432),
    )
}

private fun AndroidComposeTestRule<*, ComponentActivity>.showStaged(skin: TetherSkin, width: androidx.compose.ui.unit.Dp? = null) {
    setContent {
        ComposerHost(skin, width) {
            Composer(
                session = ComposerFixtures.session,
                projection = ComposerFixtures.idle.projection,
                controls = SessionControlFixtures.claudeIdleControls,
                serverNow = { ComposerFixtures.BUSY_NOW },
                onSend = { _, _ -> true },
                onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                onQueueEdit = { _, _ -> },
                onQueueRemove = {},
                onRequestControls = {},
                liveness = ComposerLiveness.Live,
                initialDraft = "What changed between these two runs?",
                controlActions = SessionControlFixtures.Recorder().actions(),
                attachments = ComposerAttachments(stagedFixture, { emptyList() }, {}, { _, _ -> AttachmentSendResult.Sent }),
            )
        }
    }
    // The chip thumbnail decodes off the main thread.
    waitUntil(10_000) { onAllNodes(hasTestTag("staged-attachment-thumb"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(600)
    waitForIdle()
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapSheet(skin: TetherSkin, size: String, width: androidx.compose.ui.unit.Dp? = null) {
    showStaged(skin, width)
    onNodeWithContentDescription("Add attachment").performClick()
    mainClock.advanceTimeBy(16)
    waitForIdle()
    mainClock.advanceTimeBy(600)
    waitForIdle()
    captureScreenRoboImage("src/test/screenshots/attach-sheet/${skin.id}-$size.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AttachSheetPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun sheet() = rule.snapSheet(skin, "phone")

    @Test fun chips() {
        rule.showStaged(skin)
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/composer-attachments/${skin.id}-phone.png", roborazziOptions = exact)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** From 48rem the sheet is the centred `min(22rem, 100vw - 1.5rem)` card. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class AttachSheetTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun sheet() = rule.snapSheet(skin, "tablet", WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark, TetherSkin.Machine).map { arrayOf<Any>(it) }
    }
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapBubble(skin: TetherSkin, loader: ToolMediaLoader, name: String) {
    mainClock.autoAdvance = false
    setContent {
        ChatHost(skin, wellHeight = 360.dp) {
            CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                Column(Modifier.padding(12.dp)) { UserBubble(BubbleFixtures.imageAndFile, timeLabel = "14:02") }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/$name/${skin.id}-phone.png", roborazziOptions = exact)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BubbleAttachmentsScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun bubble() = rule.snapBubble(skin, ToolFixtures.FakeLoader(MediaImage.Ok(ToolFixtures.checker(240, 150))), "bubble-attachments")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BubbleAttachmentsFailedScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun bubble() = rule.snapBubble(skin, ToolFixtures.FakeLoader(MediaImage.Failed), "bubble-attachments-failed")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
