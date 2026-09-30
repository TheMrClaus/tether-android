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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.clickable
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

    /** ta-28i r2: a server's words are prose: an override is a visible token, drawn and read alike. */
    @Test
    fun aServersBidiOverrideIsATokenInTheWordsAndTheAlert() {
        show("Session \u202Etnuocca\u202C not found.", fromServer = true)
        val shown = "Session \u2060\u27E8U+202E\u27E9tnuocca\u2060\u27E8U+202C\u27E9 not found."
        rule.onNodeWithContentDescription("Server error: $shown").assert(assertive)
        rule.onAllNodes(SemanticsMatcher("raw override") { node ->
            (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
                .any { it.contains('\u202E') || it.contains('\u202C') }
        }, useUnmergedTree = true).assertCountEquals(0)
    }

    /** r2: the toast is a surface: a tap on its body never reaches the control drawn under it. */
    @Test
    fun aTapOnTheToastsBodyNeverReachesWhatIsUnderIt() {
        var under = 0
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                Box(Modifier.fillMaxWidth().height(200.dp)) {
                    Box(Modifier.fillMaxWidth().height(200.dp).clickable { under++ }.testTag("under"))
                    ErrorToast(message = "Session not found.", onClose = { closed++ }, fromServer = true, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        rule.waitForIdle()
        // The body (its words), not the X.
        rule.onNodeWithTag(ERROR_TOAST_TAG).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.3f, height / 2f)) }
        rule.waitForIdle()
        assertEquals("a tap on the toast reached the control under it", 0, under)
        assertEquals(0, closed)
        // The control: outside the toast the same surface takes the tap.
        rule.onNodeWithTag("under").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 10f)) }
        rule.waitForIdle()
        assertEquals(1, under)
    }

    /**
     * r3: a real finger moves a few pixels between down and up. The toast only observes touches (it
     * consumes none), so its X still takes that tap; a consuming parent would cancel it on the move.
     */
    @Test
    fun theXTakesATapThatMovesBelowTheTouchSlop() {
        show("Session not found.", fromServer = true)
        rule.onNodeWithContentDescription("Dismiss error").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(2f, 1f))
            moveBy(androidx.compose.ui.geometry.Offset(1f, 1f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    /** r3: the arm epoch moves when the toast uncovers something, never for words that keep (or grow) its bounds. */
    @Test
    fun theArmEpochMovesWhenTheToastShrinksMovesOrGoesButNotForNewWords() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        var bounds by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(androidx.compose.ui.geometry.Rect(0f, 800f, 400f, 900f))
        var epoch = -1
        rule.setContent { epoch = com.tether.app.ui.rememberToastArmEpoch(shown, bounds) }
        rule.waitForIdle()
        assertEquals(0, epoch)
        // New words, same bounds (a server re-sending text): nothing moves.
        repeat(3) {
            rule.runOnIdle { bounds = androidx.compose.ui.geometry.Rect(0f, 800f, 400f, 900f) }
            rule.waitForIdle()
        }
        assertEquals(0, epoch)
        // It grows: it still covers everything it covered.
        rule.runOnIdle { bounds = androidx.compose.ui.geometry.Rect(0f, 760f, 400f, 900f) }
        rule.waitForIdle()
        assertEquals(0, epoch)
        // It shrinks: what it uncovered re-arms.
        rule.runOnIdle { bounds = androidx.compose.ui.geometry.Rect(0f, 840f, 400f, 900f) }
        rule.waitForIdle()
        assertEquals(1, epoch)
        // It moves.
        rule.runOnIdle { bounds = androidx.compose.ui.geometry.Rect(20f, 840f, 420f, 900f) }
        rule.waitForIdle()
        assertEquals(2, epoch)
        // It goes away.
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertEquals(3, epoch)
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
