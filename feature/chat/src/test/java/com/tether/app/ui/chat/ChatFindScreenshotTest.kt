package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T5.3: the in-chat find bar over the transcript (chat-view.tsx 3258-3304, globals.css 5691-5754):
 * `find-markdown` = "the" over the long-markdown reply (marks in the prompt, prose, a list, the
 * table and a code fence; the active occurrence in the stronger yellow, centred); `find-user` =
 * the idle session's prompt and reply marked ("readme"); `find-none` = "No matches" with the keys
 * disabled.
 */
enum class FindShot(val id: String, val query: String, val index: Int) {
    Markdown("find-markdown", "the", 4),
    User("find-user", "readme", 0),
    None("find-none", "zzz", 0),
}

private fun fixtureFor(shot: FindShot): ChatFixtures.Folded = when (shot) {
    FindShot.Markdown -> ChatFixtures.markdown
    FindShot.User, FindShot.None -> ChatFixtures.idle
}

fun ComposeContentTestRule.snapFind(shot: FindShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = fixtureFor(shot)
    val needle = findNeedle(shot.query)
    val results = findResults(fixture.projection, needle, open = true)
    val active = activeHitIndex(results, shot.index)
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            Box(Modifier.fillMaxSize()) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    onApproval = { _, _, _ -> },
                    onAnswer = { _, _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    find = TranscriptFind(results, needle, results.hits.getOrNull(active)),
                )
                val t = LocalTetherTokens.current
                ChatFindBar(
                    query = TextFieldValue(shot.query, TextRange(shot.query.length)),
                    onQueryChange = {},
                    count = findCount(needle, results, shot.index),
                    canStep = results.hits.isNotEmpty(),
                    onStep = {},
                    onClose = {},
                    focusRequester = remember { FocusRequester() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = t.css.spaceSm, end = t.css.spaceMd),
                )
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    // The centring scroll animates on the frozen clock: let it land.
    mainClock.advanceTimeBy(1_000)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChatFindPhoneScreenshotTest(private val shot: FindShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun find() = rule.snapFind(shot, skin, shot.id, "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = FindShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ChatFindTabletScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun find() = rule.snapFind(FindShot.Markdown, skin, FindShot.Markdown.id, "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = TetherSkin.entries.map { arrayOf<Any>(it) }
    }
}

/** PLAN §4: 1.3× font scale (instrument + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ChatFindFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun find() = rule.snapFind(FindShot.Markdown, skin, "find-markdown-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(it) }
    }
}
