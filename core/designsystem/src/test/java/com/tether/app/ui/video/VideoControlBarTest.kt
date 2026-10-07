package com.tether.app.ui.video

import android.view.Surface
import android.widget.MediaController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ta-coik.68: the viewer's own control bar. */
@RunWith(RobolectricTestRunner::class)
class VideoControlBarTest {
    @get:Rule val compose = createComposeRule()

    /** A player whose place the test moves; [phase] and the rest are the interface's defaults. */
    private class Scripted : VideoPlayer {
        override var phase: VideoPhase by mutableStateOf(VideoPhase.Ready(320, 240))
        override var playing: Boolean by mutableStateOf(true)
        @Volatile var place = 0

        override val control = object : MediaController.MediaPlayerControl {
            override fun start() { playing = true }
            override fun pause() { playing = false }
            override fun getDuration() = 60_000
            override fun getCurrentPosition() = place
            override fun seekTo(pos: Int) { place = pos }
            override fun isPlaying() = playing
            override fun getBufferPercentage() = 0
            override fun canPause() = true
            override fun canSeekBackward() = true
            override fun canSeekForward() = true
            override fun getAudioSessionId() = 0
        }

        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface(surface: Surface) = Unit
        override fun pause() = Unit
        override fun release() = Unit
    }

    @Test fun anEndedClipThatReadsZeroKeepsTheLastPlaceTheBarShowed() {
        val player = Scripted().apply { place = 30_000 }
        compose.setContent { TetherTheme { VideoControlBar(player) } }
        compose.onNodeWithText("0:30").assertIsDisplayed()
        // The clip ends: the platform reads a place of 0 for an instant right after play stopped. That is not a seek to the start.
        player.place = 0
        player.playing = false
        compose.waitForIdle()
        compose.onNodeWithText("0:30").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("0:00") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
