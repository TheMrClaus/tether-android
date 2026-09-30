package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript states (T6.1 DoD), matched to the S0.4 web scenarios where one exists:
 * `idle` = idle-session, `markdown-top` = long-markdown-top (headings, lists, the literal task
 * list, the table), `markdown` = long-markdown (table bottom, both code blocks with their copy
 * keys, quote, rule), `streaming` = a message mid-stream with the caret (the web seeder cannot
 * freeze one, see parity-seed.mjs:701), `thinking-closed` / `thinking-open`, `code-copied` (the
 * copy key's check after a tap) and `load-earlier` (a v115 bounded snapshot's top).
 */
enum class ChatShot(val id: String) {
    Idle("idle"),
    MarkdownTop("markdown-top"),
    Markdown("markdown"),
    Streaming("streaming"),
    ThinkingClosed("thinking-closed"),
    ThinkingOpen("thinking-open"),
    CodeCopied("code-copied"),
    LoadEarlier("load-earlier"),
}

private fun fixtureFor(shot: ChatShot): ChatFixtures.Folded = when (shot) {
    ChatShot.Idle -> ChatFixtures.idle
    ChatShot.MarkdownTop, ChatShot.Markdown, ChatShot.CodeCopied -> ChatFixtures.markdown
    ChatShot.Streaming -> ChatFixtures.streaming
    ChatShot.ThinkingClosed, ChatShot.ThinkingOpen -> ChatFixtures.thinking
    ChatShot.LoadEarlier -> ChatFixtures.bounded
}

/** 600ms past the first frame: enter transitions settled; the caret is in its "on" half. */
private const val CaptureAtMs = 600L

fun ComposeContentTestRule.snapChat(shot: ChatShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = fixtureFor(shot)
    val listState = LazyListState()
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            ChatTranscript(
                projection = fixture.projection,
                tree = fixture.tree,
                showThinking = shot == ChatShot.ThinkingClosed || shot == ChatShot.ThinkingOpen,
                onFetchTurns = { _, _ -> },
                zone = ChatFixtures.zone,
                listState = listState,
            )
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    // Gestures run on the real clock (a tap on a full-width clickable row does not land while the
    // test clock is frozen); the capture then waits on the frozen clock again.
    mainClock.autoAdvance = true
    when (shot) {
        ChatShot.MarkdownTop, ChatShot.LoadEarlier -> {
            // A reader's drag up (disengages follow, shows Jump to latest), then the very top.
            onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
            mainClock.advanceTimeBy(CaptureAtMs)
            runOnIdle { runBlocking { listState.scrollToItem(0) } }
        }
        ChatShot.ThinkingOpen -> onNodeWithContentDescription("Thinking").performClick()
        ChatShot.CodeCopied -> onAllNodes(hasContentDescription("Copy code")).onFirst().performClick()
        else -> Unit
    }
    waitForIdle()
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every transcript state × all 6 skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatPhoneScreenshotTest(private val shot: ChatShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chat() = rule.snapChat(shot, skin, "chat-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ChatShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/**
 * The web's desktop layout (1280×800) differs: 86% bubbles (Studio 88%), an agent bubble that no
 * longer spans the well, the wider `.chat-scroll` padding and the 16rem code clamp.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ChatTabletScreenshotTest(private val shot: ChatShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chat() = rule.snapChat(shot, skin, "chat-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ChatShot.Idle, ChatShot.MarkdownTop, ChatShot.Markdown).flatMap { s ->
            TetherSkin.entries.map { arrayOf<Any>(s, it) }
        }
    }
}

/** PLAN §4: 1.3× font scale does not break the transcript (Machine + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ChatFontScaleScreenshotTest(private val shot: ChatShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun chat() = rule.snapChat(shot, skin, "chat-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ChatShot.MarkdownTop, ChatShot.Markdown, ChatShot.Streaming, ChatShot.ThinkingOpen).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
