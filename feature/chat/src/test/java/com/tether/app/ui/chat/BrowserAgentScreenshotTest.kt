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
import com.tether.app.client.AgentBox
import com.tether.app.client.AgentMarker
import com.tether.app.client.AgentUiState
import com.tether.app.client.BrowserChannel
import com.tether.app.protocol.BrowserProfileInfo
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the pane shows of the agent and of the session's browser choices (browser-pane.tsx 0bd07fdd):
 * `browser-agent-marker`: a ring over the button the agent clicks, with the "Agent" pill;
 * `browser-agent-pill`: the pill alone (the marker has run out); `browser-took-over`: "You took
 * over" in attention colours and no marker; `browser-two-tabs`: two tabs, the tab label with its
 * chevron beside the pill; `browser-profile-picked`: a named profile in use (hint names it);
 * `browser-profile-refused`: a pick the server refused, the refusal row under the hint.
 */
enum class BrowserAgentShot(val id: String) {
    Marker("browser-agent-marker"),
    Pill("browser-agent-pill"),
    TookOver("browser-took-over"),
    TwoTabs("browser-two-tabs"),
    ProfilePicked("browser-profile-picked"),
    ProfileRefused("browser-profile-refused"),
}

private const val AgentShotTag = "browser-shot"

private val ShotProfiles = listOf(BrowserProfileInfo("p1", "Work"), BrowserProfileInfo("p2", "Personal"))

/** A stand-in page: a heading and one button, the thing the agent's marker lands on. */
private fun agentFrame(): ImageBitmap {
    val bmp = ImageBitmap(390, 844)
    val c = Canvas(bmp)
    fun rect(color: Color, x: Float, y: Float, w: Float, h: Float) = c.drawRect(Rect(Offset(x, y), Size(w, h)), Paint().apply { this.color = color })
    rect(Color(0xFFF3F4F8), 0f, 0f, 390f, 844f)
    rect(Color(0xFF1F2937), 24f, 24f, 120f, 12f)
    rect(Color(0xFFE5E7EB), 96f, 120f, 190f, 56f)
    rect(Color(0xFFD1D5DB), 24f, 220f, 300f, 12f)
    rect(Color(0xFFD1D5DB), 24f, 248f, 240f, 12f)
    return bmp
}

private val ButtonBox = AgentBox(90.0, 114.0, 202.0, 68.0)

private fun agentUiFor(shot: BrowserAgentShot): AgentUiState = when (shot) {
    BrowserAgentShot.Marker -> AgentUiState(running = true, kind = "click", marker = AgentMarker(1, "click", ButtonBox, until = Long.MAX_VALUE))
    BrowserAgentShot.Pill, BrowserAgentShot.TwoTabs -> AgentUiState(running = true, kind = "click")
    BrowserAgentShot.TookOver -> AgentUiState(tookOver = true)
    else -> AgentUiState()
}

private fun paneUi(shot: BrowserAgentShot): BrowserChannel.Ui {
    val tabs = if (shot == BrowserAgentShot.TwoTabs) {
        listOf(
            BrowserChannel.Tab(1, false, "https://localhost:3000/", "Dev server", "t1"),
            BrowserChannel.Tab(2, true, "https://localhost:3000/docs", "Documentation for the dev server", "t2"),
        )
    } else {
        emptyList()
    }
    return BrowserChannel.Ui(
        connected = true,
        state = BrowserChannel.State(
            url = "https://localhost:3000/",
            title = "Dev server",
            viewport = BrowserChannel.Viewport(390, 844, mobile = true, deviceScaleFactor = 1.0),
            tabs = tabs,
            profile = if (shot == BrowserAgentShot.ProfilePicked) BrowserChannel.PaneProfile("p1", "Work") else null,
        ),
        profileError = if (shot == BrowserAgentShot.ProfileRefused) "The browser profile \"Work\" is in use by the session \"Fix login\". Chromium allows one browser per profile: close that session's browser or pick another profile." else null,
    )
}

@Composable
private fun AgentPane(shot: BrowserAgentShot) {
    BrowserPaneContent(
        ui = paneUi(shot),
        frame = agentFrame(),
        actions = BrowserPaneActions({}, { _, _, _ -> }, {}, {}, {}),
        defaultUrl = "localhost:3000",
        agent = agentUiFor(shot),
        profileList = ShotProfiles,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapAgent(shot: BrowserAgentShot, skin: TetherSkin, size: String, width: Dp, height: Dp, tablet: Boolean) {
    setContent {
        TetherTheme(choiceFor(skin)) {
            androidx.compose.runtime.CompositionLocalProvider(com.tether.app.ui.theme.LocalReducedMotion provides true) {
                Box(Modifier.size(width, height).background(chatWellColor(com.tether.app.ui.theme.LocalTetherTokens.current)).testTag(AgentShotTag)) {
                    if (tablet) {
                        BrowserSidePanel(Modifier.align(Alignment.TopEnd)) { AgentPane(shot) }
                    } else {
                        AgentPane(shot)
                    }
                }
            }
        }
    }
    waitForIdle()
    onNodeWithTag(AgentShotTag).captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class BrowserAgentPhoneScreenshotTest(private val shot: BrowserAgentShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapAgent(shot, skin, "phone", 412.dp, 780.dp, tablet = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = BrowserAgentShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class BrowserAgentTabletScreenshotTest(private val shot: BrowserAgentShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun pane() = rule.snapAgent(shot, skin, "tablet", 1280.dp, 800.dp, tablet = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = BrowserAgentShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
