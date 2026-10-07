package com.tether.app.ui.chat

import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ActivityScenario
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.MediaVideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ta-coik.68 round 5 (F-1): a clip that plays inline keeps playing, from where it was, through what a rotation does to
 * the app on a device: the chat's composition is disposed and composed again (the shell switch; the activity handles the
 * configuration change itself) while the ViewModel, its registry and the clip's player stay, and the frame view's surface
 * goes and a new one comes. The platform player is modelled, with what the device was seen to do to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolClipsPlaybackRotationTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** A platform player with a place of its own (see MediaVideoPlayerTest.Platform). */
    private class Platform : MediaPlayer() {
        @Volatile var pos = 0
        @Volatile var running = false
        val dur = 120_000
        val seeks = java.util.Collections.synchronizedList(mutableListOf<Int>())
        var prepared: MediaPlayer.OnPreparedListener? = null
        var completed: MediaPlayer.OnCompletionListener? = null
        @Volatile var rewindsTo: Int? = null

        override fun setDataSource(dataSource: MediaDataSource) = Unit
        override fun prepareAsync() = Unit
        override fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) { prepared = l }
        override fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) { completed = l }
        override fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) = Unit
        override fun setOnVideoSizeChangedListener(l: MediaPlayer.OnVideoSizeChangedListener?) = Unit
        override fun setOnBufferingUpdateListener(l: MediaPlayer.OnBufferingUpdateListener?) = Unit
        override fun setSurface(surface: Surface?) {
            val to = rewindsTo
            if (running && to != null && pos > to) pos = to
        }
        override fun start() { running = true }
        override fun pause() { running = false }
        override fun seekTo(msec: Long, mode: Int) { seeks += msec.toInt(); pos = msec.toInt() }
        override fun getCurrentPosition() = pos
        override fun getDuration() = dur
        override fun isPlaying() = running
        override fun getVideoWidth() = 320
        override fun getVideoHeight() = 240
        override fun getAudioSessionId() = 0
        override fun reset() = Unit
        override fun release() = Unit
        fun ends() { pos = dur; running = false; completed?.onCompletion(this) }
    }

    private val item = ToolMediaItem("video", "video/mp4", ClipFixtures.src)
    private val server = ClipServer(park = true)
    private val platforms = mutableListOf<Platform>()
    private val identity = MutableStateFlow<String?>("${ClipFixtures.ORIGIN}#1")
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var registry: ToolClipRegistry? = null
    private var shell by mutableStateOf(0)

    @Before fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                ChatHost(TetherSkin.StudioDark) {
                    // Each shell's chat screen is a composition of its own: the key stands for PhoneShell / ExpandedShell.
                    key(shell) {
                        val vm = viewModel<ToolClipsViewModel>(key = "tool-clips") {
                            ToolClipsViewModel(
                                create = { scope ->
                                    ToolClipRegistry(
                                        server, RuntimeEnvironment.getApplication().cacheDir, { ClipFixtures.ORIGIN }, scope,
                                        newPlayer = { Platform().also { platforms += it } },
                                        // Inline: the test moves the modelled platform's place itself, between calls.
                                        playerThread = { it.run() },
                                    )
                                },
                                identity = identity,
                            )
                        }
                        registry = vm.registry
                        CompositionLocalProvider(
                            LocalToolMediaLoader provides ToolFixtures.FakeLoader(),
                            LocalToolClips provides vm.registry,
                            LocalVideoSurfaceEnabled provides false,
                        ) {
                            ToolMediaRow(listOf(item))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        scenario.close()
    }

    private fun surface() = Surface(SurfaceTexture(0))

    private fun until(what: String, check: () -> Boolean) {
        val limit = System.nanoTime() + 10_000_000_000L
        // The player's main scope is the ViewModel's (the main looper): its delays run as the looper's clock moves.
        while (!check() && System.nanoTime() < limit) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            Thread.sleep(5)
        }
        assertTrue("never: $what", check())
    }

    /** Play pressed, the platform prepared and its first surface attached: the clip plays on [Platform]. */
    private fun playing(): Pair<com.tether.app.ui.video.VideoPlayer, Platform> {
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.waitForIdle()
        until("the platform player was made") { platforms.isNotEmpty() }
        val platform = platforms[0]
        until("it listens") { platform.prepared != null }
        platform.prepared?.onPrepared(platform)
        val player = registry!!.clip(item.src)!!.inline.player!!
        player.attachSurface(surface())
        player.control.start()
        return player to platform
    }

    /** A rotation as the app sees it: the row's frame view goes (its surface with it), the chat is composed again, a new frame view comes. */
    private fun rotate(player: com.tether.app.ui.video.VideoPlayer, old: Surface): Surface {
        player.detachSurface(old)
        compose.runOnIdle { shell += 1 }
        compose.waitForIdle()
        return surface().also { player.attachSurface(it) }
    }

    @Test fun aPlayingClipKeepsItsPlaceThroughARotationWhenTheSurfaceChangeRewindsIt() {
        val (player, platform) = playing()
        val first = surface().also { player.attachSurface(it) }
        platform.pos = 62_667
        platform.rewindsTo = 53_000
        val before = registry
        val second = rotate(player, first)
        assertTrue("the same registry and player", before === registry && registry!!.clip(item.src)!!.inline.player === player)
        assertEquals(1, platforms.size)
        until("it is back where it was (at ${platform.pos})") { platform.pos >= 62_000 }
        assertTrue(player.playing && platform.running)
        player.detachSurface(second)
    }

    @Test fun anEndedClipReplayedThenRotatedKeepsPlayingAndIsNotSoughtToItsEnd() {
        val (player, platform) = playing()
        val first = surface().also { player.attachSurface(it) }
        platform.ends()
        val ended = rotate(player, first)
        player.control.start()
        platform.pos = 700
        // The end of the frame seek a new surface made at the end arrives late, after the replay began.
        platform.completed?.onCompletion(platform)
        platform.pos = 20_000
        platform.seeks.clear()
        val second = rotate(player, ended)
        val third = rotate(player, second)
        assertTrue("still playing after the rotations", player.playing && platform.running)
        assertTrue("never sought to its end: ${platform.seeks}", platform.seeks.none { it >= platform.dur - 1_000 })
        assertEquals("from where it was", 20_000, platform.pos)
        player.detachSurface(third)
    }
}
