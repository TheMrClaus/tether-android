package com.tether.app.ui.files

import com.tether.app.client.FilesResult
import com.tether.app.ui.files.FilesFixtures.ROOT
import com.tether.app.ui.files.FilesFixtures.file
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-1u4 in the browser's state: a video opens at once and plays nothing, has no size cap, and its
 * player is released on EVERY way out of the selection. (SVG: FileBrowserSvgTest.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserMediaTest {
    private val files = FakeFiles().apply {
        listings[ROOT] = FilesResult.Ok(FilesFixtures.listing(entries = listOf(FilesFixtures.docs, file("clip.mp4", 700), file("logo.svg", 90))))
        listings["${ROOT}/docs"] = FilesResult.Ok(FilesFixtures.listing("${ROOT}/docs", emptyList()))
    }
    private val platform = FakePlatform()
    private val clip = file("clip.mp4", 700)

    private fun TestScope.browser(): FileBrowserState =
        FileBrowserState(files, platform, CoroutineScope(StandardTestDispatcher(testScheduler))).apply {
            cwd = ROOT
            sessionName = FilesFixtures.SESSION
        }

    // --- video ----------------------------------------------------------------------------------

    @Test fun selectingAVideoOpensAPlayerAtOnceAndFetchesNothingElse() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        val before = files.calls.size
        s.selectFile(clip)
        advanceUntilIdle()
        assertEquals(listOf("openVideo ${clip.path}"), platform.calls.filter { it.startsWith("openVideo") })
        assertEquals("the state itself reads no bytes: the player streams by Range", before, files.calls.size)
        val player = platform.players.single()
        assertSame(player, s.video)
        assertFalse("no autoplay", player.playing)
        assertFalse(s.previewLoading)
        assertEquals("", s.previewError)
    }

    @Test fun thereIsNoSizeCapOnAVideo() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(file("movie.mkv.mp4", 40L * 1024 * 1024 * 1024))
        assertNotNull(s.video)
        assertEquals("", s.previewError)
        assertEquals(1, platform.players.size)
    }

    @Test fun aFailedVideoShowsTheWebsCopy() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        platform.players.single().fail()
        assertEquals("This video could not be played.", s.previewError)
        assertEquals(VideoPhase.Failed, s.video!!.phase)
    }

    @Test fun aLateFailureOfAReplacedVideoSaysNothing() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val old = platform.players.single()
        s.selectFile(file("other.mp4", 5))
        old.fail()
        assertEquals("", s.previewError)
    }

    @Test fun backReleasesThePlayer() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.clearSelection()
        assertEquals(1, player.releases)
        assertNull(s.video)
    }

    @Test fun anotherSelectionReleasesThePlayer() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.selectFile(FilesFixtures.readme)
        assertEquals(1, player.releases)
        assertNull(s.video)
        // Another video: the first is gone, the second is open.
        s.selectFile(clip)
        val second = platform.players.last()
        s.selectFile(file("again.webm", 3))
        assertEquals(1, second.releases)
        assertEquals(1, platform.players.first().releases)
        assertEquals("the third is the only one open", 3, platform.players.size)
        assertFalse(platform.players.last().released)
    }

    @Test fun aFolderChangeReleasesThePlayer() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.loadDirectory("${ROOT}/docs")
        assertEquals(1, player.releases)
        assertNull(s.video)
    }

    @Test fun closingTheDialogReleasesThePlayer() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.close()
        assertEquals(1, player.releases)
        assertNull(s.video)
    }

    @Test fun disposingTheBrowserReleasesThePlayer() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.dispose()
        assertEquals(1, player.releases)
    }

    @Test fun fullscreenKeepsThePlayerAndDoesNotRestartIt() = runTest {
        val s = browser()
        s.open()
        advanceUntilIdle()
        s.selectFile(clip)
        val player = platform.players.single()
        s.toggleFullscreen()
        s.toggleFullscreen()
        assertEquals(0, player.releases)
        assertEquals(1, platform.players.size)
        assertSame(player, s.video)
    }
}
