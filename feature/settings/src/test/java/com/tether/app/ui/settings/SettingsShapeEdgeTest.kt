package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.tether.app.ui.components.dialogScrim
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * ta-v8dt (W21): the settings dialog is ONE shape at every width, the web at tether 29537e0 (L1 spec section 3; studio.css
 * 511-520, 556, 566, 957-958): the scrim behind it (never graphite), the studio shadow, a card sized to its content and
 * centred, 12 in from the sides at 640 dp and under (radius 14, at most h - 32 tall) and 24 above (radius 16, at most
 * h - 48), the body at min(460, half the height). The frame is read through the real width source (`settingsLayout()`),
 * not a passed layout. dp = px (mdpi), 900 tall, over a white window so the scrim's composite is known.
 */
abstract class SettingsShapeEdgeBase(private val narrow: Boolean) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState()
    private var graphite = Color.Unspecified
    private var scrimOverWhite = Color.Unspecified

    private fun show() {
        compose.setContent {
            TetherTheme(TetherSkin.StudioDark.mode) {
                val t = LocalTetherTokens.current
                graphite = t.graphite
                scrimOverWhite = dialogScrim(t).compositeOver(Color.White)
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        SettingsFrame(
                            prefs = store.prefs, serverUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null), state = state,
                            restartRequired = false, currentWorkspace = CURRENT, onClose = {}, layout = settingsLayout(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun dist(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    @Test fun theCardIsTheWebsShapeAtThisWidth() {
        show()
        val card = compose.onNodeWithTag(SettingsDialogTags.Dialog).fetchSemanticsNode().boundsInRoot
        val w = if (narrow) 640f else 641f
        val left = if (narrow) 12f else 24f
        val maxH = if (narrow) 868f else 852f
        assertEquals("card left", left, card.left, 0.5f)
        assertEquals("card width", w - 2 * left, card.width, 0.5f)
        assertEquals("centred", card.top, 900f - card.bottom, 1f)
        assertTrue("height ${card.height} <= $maxH", card.height <= maxH + 0.5f)
        val body = compose.onNodeWithTag(SettingsDialogTags.Body).fetchSemanticsNode().boundsInRoot
        assertTrue("body ${body.height} >= 450", body.height >= 449.5f)
        println("W21-RECORD settings w=${w.toInt()} card=${card.left},${card.top} ${card.width}x${card.height} body=${body.height}")

        val px = compose.onRoot().captureToImage().toPixelMap()
        fun at(x: Float, y: Float) = px[x.toInt(), y.toInt()]
        val midY = (card.top + card.bottom) / 2f
        assertTrue("(6, midY) is the scrim (under the shadow's edge), not graphite", dist(at(6f, midY), scrimOverWhite) < dist(at(6f, midY), graphite))
        assertTrue("and the window beside the scrim is lighter than the card", at(6f, midY).red > graphite.red)
        assertTrue("the corner (left+1, top+1) is the scrim, not graphite", dist(at(card.left + 1, card.top + 1), scrimOverWhite) < dist(at(card.left + 1, card.top + 1), graphite))
        assertTrue("(left+14, top+14) is graphite", dist(at(card.left + 14, card.top + 14), graphite) < 0.02f)
        // The diagonal probe at 4.4 dp: the r14 arc crosses it at 4.10, the r16 arc at 4.69.
        val d = at(card.left + 4.4f, card.top + 4.4f)
        if (narrow) assertTrue("r14: (4.4, 4.4) is graphite", dist(d, graphite) < dist(d, scrimOverWhite))
        else assertTrue("r16: (4.4, 4.4) is the scrim", dist(d, scrimOverWhite) < dist(d, graphite))
        val below = at((card.left + card.right) / 2f, card.bottom + 8f)
        assertTrue("the shadow darkens 8 dp below the card", below.red < scrimOverWhite.red - 0.005f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h900dp-mdpi")
class SettingsShapeEdge640Test : SettingsShapeEdgeBase(narrow = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w641dp-h900dp-mdpi")
class SettingsShapeEdge641Test : SettingsShapeEdgeBase(narrow = false)
