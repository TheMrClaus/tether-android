package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ToolMediaResult
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.video.LocalVideoSurfaceEnabled
import com.tether.app.ui.video.VideoPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.68: the inline tool-media clip's states (design spec C-design §3), Studio light and dark,
 * phone and tablet: idle (the 2:1 box, play disc, expand key), loading (spinner instead of the disc),
 * error ("Video unavailable") and Blocked (the sign-in gateway's two lines). The frame views are off
 * (a Robolectric window has no surface); the box, edge, disc, key and copy are what these shoot.
 */
private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private enum class ClipShot(val id: String) {
    Idle("idle"), Loading("loading"), Error("error"), Blocked("blocked"),

    /** Prepared at a known 1280x720: the box resized to the clip, the edge gone. */
    Ready("ready"),

    /** Ready, and the source is waiting for bytes: the held frame's centred spinner. */
    Buffering("buffering"),

    /** Played at 720x1280, then released: idle again at the remembered size. */
    Remembered("remembered"),
    TooLarge("too-large"),

    /** Idle in a row narrower than 300 dp: W x W/2. */
    Narrow("narrow"),
}

private fun AndroidComposeTestRule<*, ComponentActivity>.snapClip(shot: ClipShot, skin: TetherSkin, device: String, wellHeight: Dp, wellWidth: Dp?) {
    val server = when (shot) {
        ClipShot.Idle, ClipShot.Narrow -> ClipServer()
        ClipShot.Loading, ClipShot.Ready, ClipShot.Buffering, ClipShot.Remembered -> ClipServer(park = true)
        ClipShot.TooLarge -> ClipServer(answer = ToolMediaResult.TooLarge)
        ClipShot.Error -> ClipServer(answer = ToolMediaResult.Failed(500))
        ClipShot.Blocked -> ClipServer(answer = ToolMediaResult.Blocked(302))
    }
    val failures = mutableListOf<() -> Unit>()
    val stubs = mutableListOf<StubVideoPlayer>()
    val registry = ToolClipRegistry(
        server, activity.cacheDir, { ClipFixtures.ORIGIN }, CoroutineScope(Dispatchers.Unconfined),
        makePlayer = { reader, failed -> failures += failed; StubVideoPlayer(reader, failed).also { stubs += it } },
    )
    try {
        val clip = checkNotNull(registry.clip(ClipFixtures.src))
        if (shot != ClipShot.Idle && shot != ClipShot.Narrow) clip.inline.play()
        if (shot == ClipShot.Error || shot == ClipShot.Blocked || shot == ClipShot.TooLarge) {
            waitUntil(20_000) { clip.currentDownload?.outcome() != null }
            failures.single().invoke()
        }
        mainClock.autoAdvance = false
        setContent {
            ChatHost(skin, wellHeight = wellHeight, wellWidth = wellWidth) {
                CompositionLocalProvider(
                    LocalToolMediaLoader provides ToolFixtures.FakeLoader(),
                    LocalToolClips provides registry,
                    LocalVideoSurfaceEnabled provides false,
                ) {
                    Column(Modifier.padding(12.dp)) { ToolMediaRow(listOf(ToolMediaItem("video", "video/mp4", ClipFixtures.src))) }
                }
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        if (shot == ClipShot.Ready || shot == ClipShot.Buffering) {
            runOnIdle {
                stubs.single().phase = VideoPhase.Ready(1280, 720)
                if (shot == ClipShot.Buffering) stubs.single().buffering = true
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
        }
        if (shot == ClipShot.Remembered) {
            // The size is learned by the row (a player reported it), then the clip is released: idle at that size.
            runOnIdle {
                stubs.single().phase = VideoPhase.Ready(720, 1280)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            mainClock.advanceTimeBy(600)
            waitForIdle()
            runOnIdle {
                registry.releaseAll()
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag(WellTag).captureRoboImage("src/test/screenshots/tool-media-video-${shot.id}/${skin.id}-$device.png", roborazziOptions = exact)
    } finally {
        registry.releaseAll()
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolMediaVideoPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun idle() = rule.snapClip(ClipShot.Idle, skin, "phone", 240.dp, null)

    @Test fun loading() = rule.snapClip(ClipShot.Loading, skin, "phone", 240.dp, null)

    @Test fun error() = rule.snapClip(ClipShot.Error, skin, "phone", 240.dp, null)

    @Test fun blocked() = rule.snapClip(ClipShot.Blocked, skin, "phone", 240.dp, null)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ToolMediaVideoTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun idle() = rule.snapClip(ClipShot.Idle, skin, "tablet", 240.dp, WellWidthTablet)

    @Test fun loading() = rule.snapClip(ClipShot.Loading, skin, "tablet", 240.dp, WellWidthTablet)

    @Test fun error() = rule.snapClip(ClipShot.Error, skin, "tablet", 240.dp, WellWidthTablet)

    @Test fun blocked() = rule.snapClip(ClipShot.Blocked, skin, "tablet", 240.dp, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

/** ta-coik.68 (design ruling, smallest fix): the box after the size is known, remembered, too large, and in a narrow row. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ToolMediaVideoKnownSizeTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun ready() = rule.snapClip(ClipShot.Ready, skin, "tablet", 360.dp, WellWidthTablet)

    @Test fun buffering() = rule.snapClip(ClipShot.Buffering, skin, "tablet", 360.dp, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolMediaVideoStatesPhoneScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun remembered() = rule.snapClip(ClipShot.Remembered, skin, "phone", 400.dp, null)

    @Test fun tooLarge() = rule.snapClip(ClipShot.TooLarge, skin, "phone", 240.dp, null)

    @Test fun narrow() = rule.snapClip(ClipShot.Narrow, skin, "phone", 240.dp, 280.dp)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
