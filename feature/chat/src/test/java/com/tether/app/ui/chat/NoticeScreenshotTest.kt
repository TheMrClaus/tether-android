package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The T6.6 visual states. Transcript: `fallback` (a Claude model-fallback notice), `codex` (a rich
 * Codex turn's compaction + info + error notices), `session` (external advancement and background
 * loss), `interrupted` (a turn Tether interrupted), `limit` (the limit card), `scheduled` (the
 * armed automatic resume), `retry` (an api_retry between HTTP attempts), `outcome-unknown` (the web's `notices` scenario). Composer: `read-only` and
 * `handoff`. Built from the reducer's own event shapes (NoticeFixtures); the web's fake engine
 * seeds none of them (docs/parity/screens/notices/README.md).
 */
enum class NoticeShot(val id: String, val composer: Boolean = false) {
    Fallback("fallback"),
    Codex("codex"),
    Session("session"),
    Interrupted("interrupted"),
    Limit("limit"),
    Scheduled("scheduled"),
    Retry("retry"),
    OutcomeUnknown("outcome-unknown"),
    ReadOnly("read-only", composer = true),
    Handoff("handoff", composer = true),
}

private const val CaptureAtMs = 700L

private fun fixtureFor(shot: NoticeShot): ChatFixtures.Folded = when (shot) {
    NoticeShot.Fallback -> NoticeFixtures.claudeFallback
    NoticeShot.Codex -> NoticeFixtures.codexNotices
    NoticeShot.Session -> NoticeFixtures.sessionNotices
    NoticeShot.Interrupted -> NoticeFixtures.interrupted
    NoticeShot.Limit -> NoticeFixtures.limit
    NoticeShot.Scheduled -> NoticeFixtures.scheduled
    NoticeShot.Retry -> NoticeFixtures.apiRetry
    NoticeShot.OutcomeUnknown -> NoticeFixtures.outcomeUnknown
    else -> ChatFixtures.idle
}

fun ComposeContentTestRule.snapNotice(shot: NoticeShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = fixtureFor(shot)
    val tag = if (shot.composer) ComposerTag else WellTag
    setContent {
        if (shot.composer) {
            val target = ComposerFixtures.session.copy(id = "sess-0002", name = "Parser rewrite")
            val session = when (shot) {
                NoticeShot.ReadOnly -> ComposerFixtures.session.copy(readOnly = true, provider = "codex", model = "gpt-5.3-codex")
                else -> ComposerFixtures.session.copy(handedOffTo = target.id)
            }
            ComposerHost(skin, wellWidth) {
                Composer(
                    session = session,
                    projection = fixture.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { _, _ -> false },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    handoffTarget = target,
                )
            }
        } else {
            ChatHost(skin, wellHeight, wellWidth) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = LazyListState(),
                    richCodex = shot == NoticeShot.Codex,
                    notices = NoticeFixtures.live,
                    showTimeline = false,
                )
            }
        }
    }
    // Settled, so the limit card is drawn as it stands.
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    onNodeWithTag(tag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** Every T6.6 state × both Studio skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NoticePhoneScreenshotTest(private val shot: NoticeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun notices() = rule.snapNotice(shot, skin, "notice-${shot.id}", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = NoticeShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout: notices and cards at 94% of the column, the limit keys in one row. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class NoticeTabletScreenshotTest(private val shot: NoticeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun notices() = rule.snapNotice(shot, skin, "notice-${shot.id}", "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(NoticeShot.Fallback, NoticeShot.Session, NoticeShot.Limit, NoticeShot.OutcomeUnknown, NoticeShot.Handoff).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the surfaces (Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class NoticeFontScaleScreenshotTest(private val shot: NoticeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun notices() = rule.snapNotice(shot, skin, "notice-${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(NoticeShot.Session, NoticeShot.Limit, NoticeShot.Handoff).flatMap { s ->
            listOf(TetherSkin.StudioDark, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
