package com.tether.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.GlobalSearchResults
import com.tether.app.protocol.SearchHit
import com.tether.app.ui.GlobalSearchForm
import com.tether.app.ui.sidebar.SidebarFixtures
import com.tether.app.ui.sidebar.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3: the global search modal's states (components/global-search.tsx), over the dashboard's
 * mineral page. `hint` = just opened (under two characters); `results` = "parity" with the Codex
 * chip on, Past week and This workspace only, three hits (one per provider; the snippet's
 * "· N matches" only past one); `pending` = the same query in flight (the spinner, the held hits
 * and their count); `searching` = typed ahead of the pending request ("Searching…");
 * `no-match` = an answered query with no hits.
 */
enum class GlobalSearchShot(val id: String) {
    Hint("global-search-hint"),
    Results("global-search-results"),
    Pending("global-search-pending"),
    Searching("global-search-searching"),
    NoMatch("global-search-no-match"),
}

private val F = SidebarFixtures
private const val MIN = 60_000L

private val hits = listOf(
    SearchHit(
        historyId = "g-1", provider = "claude", name = "Parity pass on the session sidebar", cwd = F.APP,
        updatedAt = F.NOW - 12 * MIN, snippet = "…the parity matrix now lists every sidebar row; the web keeps the order…", matchCount = 4,
    ),
    SearchHit(
        historyId = "g-2", provider = "codex", name = "Codex: parity corpus capture", cwd = "${F.ROOT}/repo",
        updatedAt = F.NOW - 5 * 60 * MIN, snippet = "…captured the parity corpus from the fake engine and vendored it…", matchCount = 1,
    ),
    SearchHit(
        historyId = "g-3", provider = "opencode", name = "Opencode: sync outbox sketch", cwd = F.DOCS,
        updatedAt = F.NOW - 3 * 24 * 60 * MIN, snippet = "…keep parity with the web outbox before adding the mirror…", matchCount = 2,
    ),
)

private fun formFor(shot: GlobalSearchShot): GlobalSearchForm = when (shot) {
    GlobalSearchShot.Hint -> GlobalSearchForm()
    GlobalSearchShot.Searching -> GlobalSearchForm(text = "parity co", providers = listOf("codex"), timeWindow = "7d", scopeToWorkspace = true)
    GlobalSearchShot.NoMatch -> GlobalSearchForm(text = "zz-nothing")
    else -> GlobalSearchForm(text = "parity", providers = listOf("codex"), timeWindow = "7d", scopeToWorkspace = true)
}

private fun resultsFor(shot: GlobalSearchShot): GlobalSearchResults = when (shot) {
    GlobalSearchShot.Hint -> GlobalSearchResults()
    GlobalSearchShot.Results -> GlobalSearchResults(requestId = 3, query = "parity", hits = hits)
    GlobalSearchShot.Pending -> GlobalSearchResults(requestId = 4, query = "parity", hits = hits.take(2), pending = true)
    GlobalSearchShot.Searching -> GlobalSearchResults(requestId = 4, query = "parity", hits = hits, pending = true)
    GlobalSearchShot.NoMatch -> GlobalSearchResults(requestId = 5, query = "zz-nothing")
}

private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapGlobalSearch(shot: GlobalSearchShot, skin: TetherSkin, size: String, name: String = shot.id) {
    mainClock.autoAdvance = false
    setContent {
        TetherTheme(choiceFor(skin)) {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Box(Modifier.fillMaxSize().background(LocalTetherTokens.current.mineral)) {
                    GlobalSearchFrame(
                        form = formFor(shot),
                        onFormChange = {},
                        results = resultsFor(shot),
                        workspaceRoot = F.ROOT,
                        now = F.NOW,
                        onClose = {},
                        onOpenHit = { _, _ -> },
                    )
                }
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every state × all 6 skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GlobalSearchPhoneScreenshotTest(private val shot: GlobalSearchShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun search() = rule.snapGlobalSearch(shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = GlobalSearchShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The desktop modal (tablet 1280×800): hint and results. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class GlobalSearchTabletScreenshotTest(private val shot: GlobalSearchShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun search() = rule.snapGlobalSearch(shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(GlobalSearchShot.Hint, GlobalSearchShot.Results).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class GlobalSearchFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun search() = rule.snapGlobalSearch(GlobalSearchShot.Results, skin, "phone", name = "global-search-results-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
