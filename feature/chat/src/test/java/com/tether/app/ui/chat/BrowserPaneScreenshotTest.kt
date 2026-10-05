package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.tether.app.client.BrowserSocketOpener
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.6: the in-console browser pane (browser-pane.tsx / browser-pane.module.css 90fbb9f).
 * `browser-live`: connected on a Mobile-preset page (the active preset, "390×844 · mobile", the Live
 * dot and the page URL); `browser-dead`: a channel that could not open ("The browser is not
 * available." and why). Phone: the full-screen sheet; tablet: the 520dp right-hand panel.
 */
enum class BrowserShot(val id: String) { Live("browser-live"), Dead("browser-dead") }

private const val ShotTag = "browser-shot"

/** A stand-in screencast frame: a page header, a hero block and three text lines. */
private fun fakeFrame(): ImageBitmap {
    val bmp = ImageBitmap(390, 844)
    val c = Canvas(bmp)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float) =
        c.drawRect(Offset(x, y).let { androidx.compose.ui.geometry.Rect(it, Size(w, h)) }, Paint().apply { this.color = color })
    rect(Color.White, 0f, 0f, 390f, 844f)
    rect(Color(0xFF1F2937), 0f, 0f, 390f, 64f)
    rect(Color(0xFF6366F1), 24f, 96f, 342f, 180f)
    for (i in 0 until 3) rect(Color(0xFFD1D5DB), 24f, 308f + i * 32f, 342f - i * 60f, 14f)
    return bmp
}

private fun uiFor(shot: BrowserShot): BrowserChannel.Ui = when (shot) {
    BrowserShot.Live -> BrowserChannel.Ui(
        connected = true,
        state = BrowserChannel.State(
            url = "https://localhost:3000/",
            title = "Dev server",
            viewport = BrowserChannel.Viewport(390, 844, mobile = true, deviceScaleFactor = 1.0),
        ),
    )
    BrowserShot.Dead -> BrowserChannel.Ui(connected = false, error = BrowserSocketOpener.SIGNED_OUT)
}

@Composable
private fun Pane(shot: BrowserShot) {
    BrowserPaneContent(
        ui = uiFor(shot),
        frame = if (shot == BrowserShot.Live) fakeFrame() else null,
        actions = BrowserPaneActions({}, { _, _, _ -> }, {}, {}, {}),
        defaultUrl = if (shot == BrowserShot.Live) "localhost:3000" else null,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapBrowser(shot: BrowserShot, skin: TetherSkin, size: String, width: Dp, height: Dp, tablet: Boolean) {
    setContent {
        TetherTheme(choiceFor(skin)) {
            androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                Box(Modifier.size(width, height).background(chatWellColor(com.tether.app.ui.theme.LocalTetherTokens.current)).testTag(ShotTag)) {
                    if (tablet) {
                        BrowserSidePanel(Modifier.align(Alignment.TopEnd)) { Pane(shot) }
                    } else {
                        Pane(shot)
                    }
                }
            }
        }
    }
    waitForIdle()
    onNodeWithTag(ShotTag).captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserPanePhoneScreenshotTest(private val shot: BrowserShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapBrowser(shot, skin, "phone", 412.dp, 780.dp, tablet = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = BrowserShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserPaneTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapBrowser(BrowserShot.Live, skin, "tablet", 1280.dp, 800.dp, tablet = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}
