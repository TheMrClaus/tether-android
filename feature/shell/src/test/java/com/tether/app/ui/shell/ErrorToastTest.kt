package com.tether.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.ERROR_TOAST_TAG
import com.tether.app.ui.ErrorToast
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.7 `.error-toast` (dashboard.tsx:1940-1946): `role="alert"` (read at once), the "Dismiss error"
 * X, and, Android's addition, a server's words under "From the server" and read as "Server error: …"
 * so they cannot pass for the app's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ErrorToastTest {
    @get:Rule val rule = createComposeRule()

    private var closed = 0

    private fun show(message: String, fromServer: Boolean) {
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) { ErrorToast(message = message, onClose = { closed++ }, fromServer = fromServer) }
        }
        rule.waitForIdle()
    }

    private val assertive = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive)

    @Test
    fun aServersWordsAreReadAsTheServers() {
        show("The secure link is reconnecting. Sign in again.", fromServer = true)
        rule.onNodeWithContentDescription("Server error: The secure link is reconnecting. Sign in again.").assert(assertive)
        // Never in the app's voice ("Error: …").
        rule.onAllNodes(SemanticsMatcher("read as the app's own") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Error: ") } == true
        }).assertCountEquals(0)
    }

    @Test
    fun theAppsOwnWordsAreAnErrorAlert() {
        show("The secure link is reconnecting. The turn was not interrupted.", fromServer = false)
        rule.onNodeWithContentDescription("Error: The secure link is reconnecting. The turn was not interrupted.").assert(assertive)
        rule.onNodeWithContentDescription("Server error:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theXDismissesIt() {
        show("Session not found.", fromServer = true)
        rule.onNodeWithContentDescription("Dismiss error").performClick()
        assertEquals(1, closed)
    }
}

/** The toast's two voices: a server's words (attributed) and the app's own. */
enum class ToastShot(val id: String, val text: String, val fromServer: Boolean) {
    Server("server", "That saved session is no longer available.", true),
    Local("local", "The secure link is reconnecting. The turn was not interrupted.", false),
}

private fun ComposeContentTestRule.snapToast(shot: ToastShot, skin: TetherSkin, name: String, size: String, tablet: Boolean) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalReducedMotion provides true) {
                val t = LocalTetherTokens.current
                // The toast as MainShell places it: bottom-centre over the workspace, 12dp in.
                Box(Modifier.fillMaxWidth().height(if (tablet) 120.dp else 140.dp).background(t.mineralDeep).testTag("toast-board")) {
                    ErrorToast(message = shot.text, onClose = {}, fromServer = shot.fromServer, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
                }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onNodeWithTag("toast-board").captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
    onNodeWithTag(ERROR_TOAST_TAG).assertExists()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ErrorToastPhoneScreenshotTest(private val shot: ToastShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun toast() = rule.snapToast(shot, skin, "error-toast-${shot.id}", "phone", tablet = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ToastShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ErrorToastTabletScreenshotTest(private val shot: ToastShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun toast() = rule.snapToast(shot, skin, "error-toast-${shot.id}", "tablet", tablet = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ToastShot.Server).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ErrorToastFontScaleScreenshotTest(private val shot: ToastShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun toast() = rule.snapToast(shot, skin, "error-toast-${shot.id}-font-1.3x", "phone", tablet = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ToastShot.Server).flatMap { s -> listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) } }
    }
}
