package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.BrowserChannel
import com.tether.app.client.BrowserPick
import com.tether.app.client.ElementDescriptor
import com.tether.app.client.HoverBox
import com.tether.app.protocol.Attachment
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.6 part 2 states (both Studio skins, phone and tablet):
 * `browser-pick` — Select elements on (violet), Screenshot on pick ticked, the hover box and its
 * `tag#id.class` tab over the page (browser-pane.tsx :231-254, module.css :155-176);
 * `composer-pick` — the composer with two picked elements as chips, one with its screenshot
 * (chat-view.tsx :4097-4120).
 */
private const val PickShotTag = "browser-pick-shot"
private val exactPick = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private fun pageFrame(): ImageBitmap {
    val bmp = ImageBitmap(390, 844)
    val c = Canvas(bmp)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float) = c.drawRect(Rect(Offset(x, y), Size(w, h)), Paint().apply { this.color = color })
    rect(Color.White, 0f, 0f, 390f, 844f)
    rect(Color(0xFF1F2937), 0f, 0f, 390f, 64f)
    rect(Color(0xFF6366F1), 24f, 96f, 342f, 180f)
    for (i in 0 until 3) rect(Color(0xFFD1D5DB), 24f, 308f + i * 32f, 342f - i * 60f, 14f)
    return bmp
}

private val pickingUi = BrowserChannel.Ui(
    connected = true,
    state = BrowserChannel.State(
        url = "https://localhost:3000/",
        title = "Dev server",
        viewport = BrowserChannel.Viewport(390, 844, mobile = true, deviceScaleFactor = 1.0),
        pickMode = true,
    ),
)

private val hovered = HoverBox(ElementDescriptor.Box(24.0, 96.0, 342.0, 180.0), "section#hero.banner")

@Composable
private fun PickingPane() {
    BrowserPaneContent(
        ui = pickingUi,
        frame = pageFrame(),
        actions = BrowserPaneActions({}, { _, _, _ -> }, {}, {}, {}),
        defaultUrl = "localhost:3000",
        hoverBox = hovered,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapPicking(skin: TetherSkin, size: String, width: Dp, height: Dp, tablet: Boolean) {
    setContent {
        TetherTheme(choiceFor(skin)) {
            androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                Box(Modifier.size(width, height).background(chatWellColor(com.tether.app.ui.theme.LocalTetherTokens.current)).testTag(PickShotTag)) {
                    if (tablet) BrowserSidePanel(Modifier.align(Alignment.TopEnd)) { PickingPane() } else PickingPane()
                }
            }
        }
    }
    waitForIdle()
    onNodeWithTag(PickShotTag).captureRoboImage("src/test/screenshots/browser-pick/${skin.id}-$size.png", roborazziOptions = exactPick)
}

private val pickedShot = BrowserPick(
    "pick-1",
    ElementDescriptor(selector = "section#hero.banner", tag = "section", name = "Welcome back"),
    Attachment("section-1.jpg", "image/jpeg", "QUJD"),
)
private val pickedPlain = BrowserPick("pick-2", ElementDescriptor(selector = "nav > a:nth-of-type(2)", tag = "a"), null)

@Composable
private fun PickComposer(skin: TetherSkin, width: Dp?) {
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
            initialDraft = "Make the hero shorter",
            tree = ComposerFixtures.idle.tree,
            controlActions = SessionControlFixtures.Recorder().actions(),
            browser = ComposerBrowser(open = true) {},
            browserPicks = ComposerPicks(listOf(pickedShot, pickedPlain), "https://localhost:3000/", {}, {}, { _, _ -> com.tether.app.client.AttachmentSendResult.Sent }),
        )
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPickPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapPicking(skin, "phone", 412.dp, 780.dp, tablet = false)

    @Test fun composer() {
        rule.mainClock.autoAdvance = false
        rule.setContent { PickComposer(skin, null) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/composer-pick/${skin.id}-phone.png", roborazziOptions = exactPick)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserPickTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapPicking(skin, "tablet", 1280.dp, 800.dp, tablet = true)

    @Test fun composer() {
        rule.mainClock.autoAdvance = false
        rule.setContent { PickComposer(skin, 720.dp) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithTag(ComposerTag).captureRoboImage("src/test/screenshots/composer-pick/${skin.id}-tablet.png", roborazziOptions = exactPick)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
