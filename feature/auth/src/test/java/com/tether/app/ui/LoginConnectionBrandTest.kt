package com.tether.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tether.app.client.InMemorySettings
import com.tether.app.client.RealTetherClient
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.45: studio-login.tsx:71-79 — the brand panel's connection figure carries the claude,
 * codex and opencode marks in their brand tiles (globals.css 11204-11224); hidden where the web hides
 * it (the compact, phone-width panel). ta-coik.48: the web stacks the panels and drops the figure only
 * at `max-width: 700px` (studio-login.module.css), so it shows from 701dp up, not from 840dp.
 */
abstract class LoginConnectionBase {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    protected fun figureShown(): Boolean {
        launch()
        rule.waitForIdle()
        return rule.onAllNodesWithTag(LoginTags.Connection, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    protected fun launch() = rule.setContent {
        TetherTheme(TetherSkin.StudioDark.mode) {
            LoginScreen(client = RealTetherClient(settings = InMemorySettings(), httpClient = OkHttpClient(), scope = scope), surface = LoginSurface.Studio)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class LoginConnectionTabletTest : LoginConnectionBase() {
    @Test
    fun theConnectionFigureShowsTheHarnessMarksInTheirBrandTiles() {
        launch()
        val node = rule.onNodeWithTag(LoginTags.Connection, useUnmergedTree = true)
        assertEquals(
            listOf("Your coding agents connect through Tether to your laptop and phone"),
            node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        val px = node.captureToImage().toPixelMap()
        val out = HashMap<Color, Int>()
        for (x in 0 until px.width) for (y in 0 until px.height) out.merge(px[x, y], 1, Int::plus)
        // 34x40 tiles at mdpi: one terracotta, two ink.
        assertTrue("claude tile", (out[Color(0xFFD97757)] ?: 0) > 34 * 40 / 2)
        assertTrue("codex and opencode tiles", (out[Color(0xFF0D0D0D)] ?: 0) > 34 * 40)
        assertTrue("paper marks", (out[Color(0xFFFFFFFF)] ?: 0) > 50)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class LoginConnectionPhoneTest : LoginConnectionBase() {
    @Test
    fun theCompactPanelHasNoConnectionFigure() {
        launch()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(LoginTags.Connection, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w760dp-h900dp-mdpi")
class LoginConnectionMediumTest : LoginConnectionBase() {
    @Test
    fun theFigureShowsBetween700And840dp() = assertTrue(figureShown())
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w701dp-h900dp-mdpi")
class LoginConnectionJustAboveBreakpointTest : LoginConnectionBase() {
    @Test
    fun theFigureShowsJustAbove700dp() = assertTrue(figureShown())
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w700dp-h900dp-mdpi")
class LoginConnectionAtBreakpointTest : LoginConnectionBase() {
    // `@media (max-width: 700px)` includes 700 itself: the compact panel, no figure.
    @Test
    fun theFigureIsHiddenAt700dp() = assertFalse(figureShown())
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w600dp-h900dp-mdpi")
class LoginConnectionBelowBreakpointTest : LoginConnectionBase() {
    @Test
    fun theFigureIsHiddenBelow700dp() = assertFalse(figureShown())
}

/**
 * ta-coik.49: studio-login.module.css `.shell { overflow: auto }` scrolls the whole two-column page,
 * so in a short wide window at a large font the brand panel's "Private by design" footer, below the
 * fold, is reached by scrolling (it was cut off: only the form panel scrolled).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w701dp-h480dp-mdpi", fontScale = 1.3f)
class LoginWideShortWindowTest : LoginConnectionBase() {
    @Test
    fun thePageScrollsToTheBrandFooter() {
        launch()
        rule.waitForIdle()
        val footer = rule.onNodeWithTag(LoginTags.BrandFooter, useUnmergedTree = true)
        val window = rule.onRoot().getUnclippedBoundsInRoot()
        val before = footer.getUnclippedBoundsInRoot()
        assertTrue("the footer starts below the fold (else this window proves nothing)", before.bottom > window.bottom)
        footer.performScrollTo()
        rule.waitForIdle()
        val after = footer.getUnclippedBoundsInRoot()
        assertTrue("the footer has its height", after.bottom - after.top >= 14.dp)
        assertTrue("the footer is wholly on screen", after.top >= window.top && after.bottom <= window.bottom)
        footer.assertIsDisplayed()
    }
}
