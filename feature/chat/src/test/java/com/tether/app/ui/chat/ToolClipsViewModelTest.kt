package com.tether.app.ui.chat

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.video.VideoPhase
import com.tether.app.ui.video.VideoPlayer
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * ta-coik.68: the registry is owned outside composition and follows the sign-in. Sign-out and a server
 * switch (and a same-server sign-out / sign-in, by the generation in the identity) release every
 * clip: their downloads stop and their partial files go; the same identity keeps them.
 */
@RunWith(RobolectricTestRunner::class)
class ToolClipsViewModelTest {
    @get:Rule val compose = createComposeRule()

    private val clip = ByteArray(2048) { 1 }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    private val src get() = "/api/tool-media/${sha256Hex(clip)}.mp4"
    private val identity = MutableStateFlow<String?>("https://a#1")
    private val made = mutableListOf<ToolClipRegistry>()
    private val players = mutableListOf<RecordingPlayer>()

    private class RecordingPlayer(val onFailed: () -> Unit) : VideoPlayer {
        override var phase: VideoPhase = VideoPhase.Opening
        override val playing = false
        override val control: android.widget.MediaController.MediaPlayerControl get() = throw UnsupportedOperationException()
        var released = false
        override fun attachSurface(surface: android.view.Surface) = Unit
        override fun detachSurface(surface: android.view.Surface) = Unit
        override fun pause() = Unit
        override fun release() { released = true }
    }

    /** A server that sends half the clip and then waits (a download in flight). */
    private val source = object : ToolMediaSource {
        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
            sink.write(clip, 0, 1000)
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private fun viewModel(graceMs: Long = ToolClipsViewModel.LEAVE_GRACE_MS) = ToolClipsViewModel(
        create = { scope ->
            ToolClipRegistry(source, RuntimeEnvironment.getApplication().cacheDir, { "https://a" }, scope, makePlayer = { _, failed -> RecordingPlayer(failed).also { players += it } })
                .also { made += it }
        },
        identity = identity,
        leaveGraceMs = graceMs,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun parts() = ToolMediaCache.dirFor(RuntimeEnvironment.getApplication().cacheDir, "https://a").list()?.filter { it.endsWith(".part") }.orEmpty()

    private fun waitForPart() {
        val until = System.nanoTime() + 20_000_000_000L
        while (parts().isEmpty() && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(1, parts().size)
    }

    @Test fun theSameIdentityKeepsThePlayingClips() {
        val vm = viewModel()
        idle()
        val first = vm.registry
        first.clip(src)!!.inline.play()
        waitForPart()
        identity.value = "https://a#1"
        idle()
        assertSame(first, vm.registry)
        assertFalse(players.single().released)
        vm.releaseAll()
    }

    @Test fun signOutAndAServerSwitchReleaseEveryClipAndItsPartial() {
        for (next in listOf<String?>(null, "https://b#1", "https://a#2")) {
            identity.value = "https://a#1"
            val vm = viewModel()
            idle()
            val first = vm.registry
            val clip = first.clip(src)!!
            clip.inline.play()
            clip.viewer.play()
            waitForPart()
            identity.value = next
            idle()
            assertTrue("released for $next", players.takeLast(2).all { it.released })
            assertEquals("its partial file is deleted for $next", emptyList<String>(), run {
                val until = System.nanoTime() + 20_000_000_000L
                while (parts().isNotEmpty() && System.nanoTime() < until) Thread.sleep(10)
                parts()
            })
            assertEquals(ClipState.Idle, clip.inline.state)
            assertNotSame("a fresh registry for $next", first, vm.registry)
        }
    }

    @Test fun leavingForGoodReleasesWhatIsPlaying() {
        val vm = viewModel()
        idle()
        vm.registry.clip(src)!!.inline.play()
        waitForPart()
        vm.releaseAll()
        assertTrue(players.single().released)
    }

    @Test fun aShellSwitchLeavesAndEntersTheChatInOnePassAndKeepsTheClips() {
        val vm = viewModel(graceMs = 500)
        idle()
        val clip = vm.registry.clip(src)!!
        clip.inline.play()
        waitForPart()
        // Phone chat disposed, expanded chat entered: the leave's release is cancelled.
        vm.chatLeft()
        vm.chatEntered()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertFalse(players.single().released)
        assertEquals(1, parts().size)
        vm.releaseAll()
    }

    @Test fun leavingTheChatForGoodReleasesAfterTheGrace() {
        val vm = viewModel(graceMs = 500)
        idle()
        vm.registry.clip(src)!!.inline.play()
        waitForPart()
        vm.registry.openViewer(listOf(ToolMediaItem("video", "video/mp4", src)), 0)
        vm.chatLeft()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertFalse("not yet", players.single().released)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertTrue(players.single().released)
        assertEquals(null, vm.registry.openViewerSrc)
    }

    @Test fun movingToAnotherSessionOrNoneReleasesTheOnesClipsAtOnce() {
        val vm = viewModel()
        idle()
        vm.onSession("s1")
        vm.registry.clip(src)!!.inline.play()
        vm.onSession("s1")
        assertFalse("the same session keeps them", players.single().released)
        vm.onSession("s2")
        assertTrue(players.single().released)
        vm.registry.clip(src)!!.inline.play()
        vm.onSession(null)
        assertTrue(players.last().released)
    }

    @Test fun theOpenViewerSurvivesTheRegistryNotTheRowsComposition() {
        val vm = viewModel()
        idle()
        vm.registry.openViewer(listOf(ToolMediaItem("video", "video/mp4", src)), 0)
        vm.chatLeft()
        vm.chatEntered()
        assertEquals(src, vm.registry.openViewerSrc)
        vm.releaseAll()
    }

    @Test fun theIdentityCarriesTheSignInGeneration() {
        assertEquals("https://a#3", ToolClipsViewModel.withGeneration("https://a", 3))
        assertEquals(null, ToolClipsViewModel.withGeneration(null, 3))
        assertFalse(ToolClipsViewModel.withGeneration("https://a", 3) == ToolClipsViewModel.withGeneration("https://a", 4))
        assertEquals("https://a", ToolClipsViewModel.identity(true, com.tether.app.client.ConnectionState.Connected, "https://a"))
        assertEquals(null, ToolClipsViewModel.identity(true, com.tether.app.client.ConnectionState.AuthRequired, "https://a"))
        assertEquals(null, ToolClipsViewModel.identity(false, com.tether.app.client.ConnectionState.Disconnected, "https://a"))
    }
}
