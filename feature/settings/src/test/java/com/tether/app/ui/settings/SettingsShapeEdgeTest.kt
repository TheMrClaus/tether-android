package com.tether.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
 * The Runtime Desk is an edge-to-edge Activity surface at both sides of the former 640dp card
 * breakpoint. The frame is read through the real width source (`settingsLayout()`), not a passed
 * layout. dp = px (mdpi), 900 tall, over a white window so every edge and corner proves coverage.
 */
abstract class SettingsShapeEdgeBase(private val narrow: Boolean) {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState()
    private var graphite = Color.Unspecified

    private fun show() {
        compose.setContent {
            TetherTheme(TetherSkin.StudioDark.mode) {
                val t = LocalTetherTokens.current
                graphite = t.graphite
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

    @Test fun theRuntimeDeskFillsTheWindowAtThisWidth() {
        show()
        val page = compose.onNodeWithTag(SettingsDialogTags.Dialog).fetchSemanticsNode().boundsInRoot
        val w = if (narrow) 640f else 641f
        assertEquals("page left", 0f, page.left, 0.5f)
        assertEquals("page top", 0f, page.top, 0.5f)
        assertEquals("page width", w, page.width, 0.5f)
        assertEquals("page height", 900f, page.height, 0.5f)

        val px = compose.onRoot().captureToImage().toPixelMap()
        fun at(x: Float, y: Float) = px[x.toInt(), y.toInt()]
        val probes = listOf(
            0f to 0f,
            (w - 1f) to 0f,
            0f to 899f,
            (w - 1f) to 899f,
            (w / 2f) to 0f,
            (w / 2f) to 899f,
            0f to 450f,
            (w - 1f) to 450f,
        )
        probes.forEach { (x, y) ->
            assertTrue("($x, $y) is graphite", dist(at(x, y), graphite) < 0.02f)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h900dp-mdpi")
class SettingsShapeEdge640Test : SettingsShapeEdgeBase(narrow = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w641dp-h900dp-mdpi")
class SettingsShapeEdge641Test : SettingsShapeEdgeBase(narrow = false)
