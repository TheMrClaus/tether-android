package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tether.app.client.ToolMediaResult
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.VideoPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * ta-coik.68 / ta-2hv: the inline clip and its lightbox as the user meets them. Idle draws a box with
 * a play disc and decodes nothing; a tap plays in the row; the expand key opens the viewer without
 * stopping the row's clip; a blocked or failed clip says so in the row and in the viewer; the
 * lightbox's Blocked copy for pictures too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolMediaVideoBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val item = ToolMediaItem("video", "video/mp4", ClipFixtures.src)
    private val players = mutableListOf<StubVideoPlayer>()
    private var registry: ToolClipRegistry? = null

    @After fun tearDown() {
        registry?.releaseAll()
    }

    private fun show(server: com.tether.app.client.ToolMediaSource, items: List<ToolMediaItem> = listOf(item), loader: ToolMediaLoader = ToolFixtures.FakeLoader()) {
        val r = ToolClipRegistry(
            server, rule.activity.cacheDir, { ClipFixtures.ORIGIN }, CoroutineScope(Dispatchers.Unconfined),
            makePlayer = { reader, failed -> StubVideoPlayer(reader, failed).also { players += it } },
        )
        registry = r
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader, LocalToolClips provides r, LocalVideoSurfaceEnabled provides false) {
                    ToolMediaRow(items)
                    ToolViewerHost()
                }
            }
        }
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        rule.waitUntil(20_000) { check() }
    }

    @Test fun idleDrawsTheBoxAndDecodesNothing() {
        val server = ClipServer()
        show(server)
        rule.onNodeWithTag("tool-media-video").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play video").assertIsDisplayed()
        rule.onNodeWithContentDescription("View video full size").assertIsDisplayed()
        assertTrue("no decoder on render", players.isEmpty())
        assertEquals("no request on render", 0, server.calls.get())
    }

    @Test fun aTapPlaysInTheRowAndShowsLoadingNotTheViewer() {
        val server = ClipServer(park = true)
        show(server)
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.waitForIdle()
        assertEquals(1, players.size)
        rule.onNodeWithContentDescription("Loading video").assertIsDisplayed()
        assertEquals("no viewer opened", 0, rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().size)
        waitFor("the one GET") { server.calls.get() == 1 }
        // The disc is gone and a second tap is ignored (nothing to play twice).
        assertEquals(0, rule.onAllNodesWithContentDescription("Play video").fetchSemanticsNodes().size)
        assertEquals(1, players.size)
        // Prepared: the row is ready and draws no spinner.
        players.single().phase = VideoPhase.Ready(1280, 720)
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Loading video").fetchSemanticsNodes().size)
        assertEquals("the row remembers the clip's size", 1280 to 720, registry!!.clip(item.src)!!.knownSize)
    }

    @Test fun theExpandKeyOpensTheViewerWhichAutoplaysAndTheRowKeepsPlaying() {
        val server = ClipServer(park = true)
        show(server)
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("View video full size").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals("the viewer has its own player", 2, players.size)
        assertFalse("the row's clip is not paused or released by the viewer (the web's two elements)", players[0].released)
        waitFor("the GET") { server.calls.get() >= 1 }
        assertEquals("both read the one download", 1, server.calls.get())
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertTrue("closing the viewer releases the viewer's player only", players[1].released)
        assertFalse(players[0].released)
    }

    @Test fun aBlockedClipSaysSoInTheRowAndInTheViewer() {
        val server = ClipServer(answer = ToolMediaResult.Blocked(302))
        show(server)
        rule.onNodeWithContentDescription("Play video").performClick()
        val clip = registry!!.clip(item.src)!!
        waitFor("settled") { clip.currentDownload?.outcome() != null }
        waitFor("the row's player") { players.size == 1 }
        rule.runOnIdle { players.single().onFailed() }
        waitFor("the row says Blocked") { rule.waitForIdle(); rule.onAllNodesWithText(MediaCopy.BLOCKED).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithText(MediaCopy.BLOCKED).assertIsDisplayed()
        rule.onNodeWithText(MediaCopy.BLOCKED_DETAIL).assertIsDisplayed()
        // The expand key stays (the web keeps the element); the viewer shows the same copy.
        val rowsDownload = clip.currentDownload
        rule.onNodeWithContentDescription("View video full size").performClick()
        // ta-wrqi / ta-ar84: the viewer is a window of its own, composed on a frame after the click, and its player is made
        // by its LaunchedEffect; waitUntil alone does not pump that window, so each wait idles first. A failure reported
        // before the viewer's player exists would reach the row's player again and the viewer would never learn of it.
        waitFor("the viewer's own player") { rule.waitForIdle(); players.size == 2 }
        waitFor("the viewer") { rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().isNotEmpty() }
        // The failed download is not reused: the viewer's play started a fresh one, and its player says "Blocked" only
        // from that download's verdict (ClipView: clip.failure() ?: Failed), so the failure is reported once it has settled.
        waitFor("the viewer's own download settled") { clip.currentDownload.let { it != null && it !== rowsDownload && it.outcome() != null } }
        rule.runOnIdle { players.last().onFailed() }
        waitFor("the viewer says Blocked too") {
            rule.waitForIdle()
            rule.onAllNodesWithText(MediaCopy.BLOCKED).fetchSemanticsNodes().size == 2 &&
                rule.onAllNodesWithText(MediaCopy.BLOCKED_DETAIL).fetchSemanticsNodes().size == 2
        }
        rule.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText(MediaCopy.BLOCKED).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText(MediaCopy.BLOCKED_DETAIL).fetchSemanticsNodes().size)
    }

    private fun failedClipSays(answer: ToolMediaResult, copy: String) {
        show(ClipServer(answer = answer))
        rule.onNodeWithContentDescription("Play video").performClick()
        val clip = registry!!.clip(item.src)!!
        waitFor("settled") { clip.currentDownload?.outcome() != null }
        rule.runOnIdle { players.single().onFailed() }
        rule.waitForIdle()
        rule.onNodeWithText(copy).assertIsDisplayed()
        assertEquals("one line, no detail", 0, rule.onAllNodesWithText(MediaCopy.BLOCKED_DETAIL).fetchSemanticsNodes().size)
    }

    /** A source whose first answer is a failed fetch, then the clip. */
    private fun failsOnce(calls: java.util.concurrent.atomic.AtomicInteger) = object : com.tether.app.client.ToolMediaSource {
        override suspend fun fetch(url: String, maxBytes: Long, sink: java.io.OutputStream): ToolMediaResult {
            if (calls.incrementAndGet() == 1) return ToolMediaResult.Failed(500)
            sink.write(ClipFixtures.bytes)
            return ToolMediaResult.Ok(ClipFixtures.bytes.size.toLong(), "video/mp4")
        }
    }

    @Test fun aTapOnTheFailedInlineBoxPlaysTheClipAgainWithAFreshDownload() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        show(failsOnce(calls))
        rule.onNodeWithContentDescription("Play video").performClick()
        val clip = registry!!.clip(item.src)!!
        waitFor("settled") { clip.currentDownload?.outcome() != null }
        rule.runOnIdle { players.single().onFailed() }
        rule.waitForIdle()
        rule.onNodeWithText(MediaCopy.VIDEO_UNAVAILABLE).assertIsDisplayed()
        // The web's failed <video> is played again from its own controls: a tap on the error does the same here.
        rule.onNodeWithText(MediaCopy.VIDEO_UNAVAILABLE).performClick()
        rule.waitForIdle()
        waitFor("a second request") { calls.get() == 2 }
        assertEquals("a new player", 2, players.size)
        assertEquals("the error is gone", 0, rule.onAllNodesWithText(MediaCopy.VIDEO_UNAVAILABLE).fetchSemanticsNodes().size)
    }

    @Test fun aTapOnTheFailedViewerPlaysTheClipAgain() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        show(failsOnce(calls))
        rule.onNodeWithContentDescription("View video full size").performClick()
        rule.waitForIdle()
        val clip = registry!!.clip(item.src)!!
        waitFor("settled") { clip.currentDownload?.outcome() != null }
        rule.runOnIdle { players.single().onFailed() }
        rule.waitForIdle()
        rule.onNodeWithText(MediaCopy.VIDEO_UNAVAILABLE).performClick()
        rule.waitForIdle()
        waitFor("a second request") { calls.get() == 2 }
        assertEquals("a new player", 2, players.size)
        assertEquals("the error is gone", 0, rule.onAllNodesWithText(MediaCopy.VIDEO_UNAVAILABLE).fetchSemanticsNodes().size)
    }

    @Test fun aFailedClipUsesItsOneLine() = failedClipSays(ToolMediaResult.Failed(500), MediaCopy.VIDEO_UNAVAILABLE)

    @Test fun aTooLargeClipUsesItsOneLine() = failedClipSays(ToolMediaResult.TooLarge, MediaCopy.VIDEO_TOO_LARGE)

    // --- the phone <-> expanded shell switch rebuilds every row (no activity recreation) ----------------

    @Test fun theOpenViewerSurvivesTheTranscriptNotComposingItsRowAtAllAfterTheShellSwitch() {
        val server = ClipServer(park = true)
        val r = ToolClipRegistry(
            server, rule.activity.cacheDir, { ClipFixtures.ORIGIN }, CoroutineScope(Dispatchers.Unconfined),
            makePlayer = { reader, failed -> StubVideoPlayer(reader, failed).also { players += it } },
        )
        registry = r
        var shell by androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader(), LocalToolClips provides r, LocalVideoSurfaceEnabled provides false) {
                    // Each shell's chat screen is its own composition (here: the key). The other shell's lazy
                    // transcript is at its bottom: the row that opened the viewer is not composed at all.
                    androidx.compose.runtime.key(shell) {
                        if (shell % 2 == 0) ToolMediaRow(listOf(item))
                        ToolViewerHost()
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.onNodeWithContentDescription("View video full size").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals(2, players.size)
        rule.runOnIdle { shell = 1 }
        rule.waitForIdle()
        assertEquals("the row is gone", 0, rule.onAllNodesWithContentDescription("Play video").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertEquals("no player was made or released by the switch", 2, players.size)
        assertTrue(players.none { it.released })
        rule.runOnIdle { shell = 2 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Video viewer").assertIsDisplayed()
        assertTrue(players.none { it.released })
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().size)
        assertTrue("only the viewer's player", players[1].released && !players[0].released)
    }

    // --- the viewer's own control bar (device finding: a held platform controller left Close dead) ------

    private fun openViewerWithPlayerReady(): StubVideoPlayer {
        show(ClipServer(park = true))
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.onNodeWithContentDescription("View video full size").performClick()
        rule.waitForIdle()
        val viewer = players[1]
        rule.runOnIdle { viewer.phase = VideoPhase.Ready(1280, 720) }
        rule.waitForIdle()
        return viewer
    }

    @Test fun theViewersBarIsHeldWhilePausedOrEndedAndCloseStaysPressable() {
        val viewer = openViewerWithPlayerReady()
        // Not playing (paused / ended): the bar is up and held, in the dialog's own window.
        rule.onNodeWithContentDescription("Play").assertIsDisplayed()
        rule.onNodeWithTag("video-seek").assertIsDisplayed()
        // The toolbar is reachable while it shows: Close is pressed and the viewer goes.
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().size)
        assertTrue(viewer.released)
    }

    @Test fun theBarPlaysPausesAndSeeksTheViewersPlayer() {
        val viewer = openViewerWithPlayerReady()
        rule.onNodeWithContentDescription("Play").performClick()
        rule.waitForIdle()
        assertTrue(viewer.playing)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.waitForIdle()
        assertFalse(viewer.playing)
        rule.onNodeWithTag("video-seek").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height / 2f)) }
        rule.waitForIdle()
        assertTrue("a tap on the bar seeks about halfway: ${viewer.seeks}", viewer.seeks.single() in 4_000..6_000)
        // The held bar stays up while paused even after a tap on the picture.
        rule.onNodeWithTag("viewer-video-tap").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test fun thePlayingBarGoesAwayAndAPictureTapBringsItBack() {
        val viewer = openViewerWithPlayerReady()
        rule.runOnIdle { viewer.control.start() }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(VideoSizing.VIEWER_BAR_MS + 500)
        rule.waitForIdle()
        assertEquals("gone after a few seconds of play", 0, rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size)
        rule.onNodeWithTag("viewer-video-tap").performClick()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    @Test fun closeWithTheClipEndedClosesForGoodAndNothingReopensItAcrossRecompositions() {
        val viewer = openViewerWithPlayerReady()
        // Ended: not playing, the bar held (the platform reports a place of 0 for an instant: nothing may depend on it).
        rule.onNodeWithContentDescription("Play").assertIsDisplayed()
        val made = players.size
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().size)
        assertTrue(viewer.released)
        assertEquals(null, registry!!.openViewerSrc)
        // Recompose over and over (state churn on the inline clip, time passing): the viewer stays closed and no
        // new player is made for it.
        rule.mainClock.autoAdvance = false
        repeat(5) {
            rule.runOnIdle { players.first().playing = !players.first().playing }
            rule.mainClock.advanceTimeBy(1_000)
        }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Video viewer").fetchSemanticsNodes().size)
        assertEquals("no player was made again", made, players.size)
        assertEquals(null, registry!!.openViewerSrc)
        rule.mainClock.autoAdvance = true
    }

    // --- the viewer's toolbar clears the system bars (device finding: Close sat under the status bar) --------

    @Test fun theViewersCloseKeyClearsTheStatusBarSoATouchOnItIsNotTheStatusBars() {
        val image = ToolMediaItem("image", "image/png", "/api/tool-media/${"a".repeat(64)}.png")
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader()) {
                    MediaLightbox(listOf(image), 0, onIndexChange = {}, onClose = {}, insets = androidx.compose.foundation.layout.WindowInsets(top = 48.dp, bottom = 24.dp))
                }
            }
        }
        rule.waitForIdle()
        val close = rule.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertTrue("Close starts below the 48 dp status bar: top=${close.top / density} dp", close.top >= 48f * density - 1f)
    }

    // --- ta-2hv: the lightbox's Blocked copy, for a picture ------------------------------------------

    @Test fun theViewerSaysBlockedForAPicture() {
        val image = ToolMediaItem("image", "image/png", "/api/tool-media/${"a".repeat(64)}.png")
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader(MediaImage.Blocked)) {
                    MediaLightbox(listOf(image), 0, onIndexChange = {}, onClose = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Image viewer").assertIsDisplayed()
        rule.onNodeWithText(MediaCopy.BLOCKED).assertIsDisplayed()
        rule.onNodeWithText(MediaCopy.BLOCKED_DETAIL).assertIsDisplayed()
    }
}
