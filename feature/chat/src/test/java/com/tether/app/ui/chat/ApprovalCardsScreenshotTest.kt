package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ConsentResult
import com.tether.app.client.consentKey
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The attention-card states (T6.3 DoD). `approval-write` = the S0.4 web scenario approval-pending
 * (a Write approval on the Approve / Deny fallback, its running call above it); the rest have no
 * web reference (the fake engine emits neither provider choices, grants, questions nor denials):
 * `approval-choices` (Codex choices, reason, working directory, network), `approval-grants` (the
 * requested permission expansion), `approval-locked` (a saved copy: "Connect to answer"),
 * `approval-sent` (after the tap), `question` (page 1 of 2), `question-validation` (page 2 with
 * the ask for an answer or a skip), `answered` (the settled records) and `denials` (both groups
 * open: a main-agent refusal after its call, a sub-agent's with its run link, an abort's words,
 * and the homeless one).
 */
enum class ApprovalShot(val id: String) {
    Write("approval-write"),
    Choices("approval-choices"),
    Grants("approval-grants"),
    Locked("approval-locked"),
    Sent("approval-sent"),
    Question("question"),
    QuestionValidation("question-validation"),
    Answered("answered"),
    Denials("denials"),
}

private const val CaptureAtMs = 600L

private fun fixtureFor(shot: ApprovalShot): ChatFixtures.Folded = when (shot) {
    ApprovalShot.Write, ApprovalShot.Locked, ApprovalShot.Sent -> ApprovalFixtures.write
    ApprovalShot.Choices -> ApprovalFixtures.choices
    ApprovalShot.Grants -> ApprovalFixtures.grants
    ApprovalShot.Question, ApprovalShot.QuestionValidation -> ApprovalFixtures.question
    ApprovalShot.Answered -> ApprovalFixtures.answered
    ApprovalShot.Denials -> ApprovalFixtures.denials
}

/** Screenshot consent: actionable (or [lock]ed), every call answered Sent, nothing leaves the test. */
private fun shotConsent(lock: ConsentLock?, decided: Set<String>) = ConsentActions(
    sessionId = "s1",
    lock = lock,
    decided = decided,
    questionUnavailable = null,
    onApproval = { _, _, _, _ -> ConsentResult.Sent },
    onAnswer = { _, _, _ -> ConsentResult.Sent },
    onOpenRun = {},
)

fun ComposeContentTestRule.snapApproval(shot: ApprovalShot, skin: TetherSkin, name: String, size: String, wellHeight: Dp, wellWidth: Dp? = null) {
    mainClock.autoAdvance = false
    val fixture = fixtureFor(shot)
    val listState = LazyListState()
    val consent = shotConsent(
        lock = if (shot == ApprovalShot.Locked) ConsentLock.Offline else null,
        decided = if (shot == ApprovalShot.Sent) setOf(consentKey("s1", "req-w")) else emptySet(),
    )
    setContent {
        ChatHost(skin, wellHeight, wellWidth) {
            CompositionLocalProviderForMedia {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone,
                    listState = listState,
                    richCodex = shot == ApprovalShot.Choices || shot == ApprovalShot.Grants,
                    consent = consent,
                )
            }
        }
    }
    mainClock.advanceTimeBy(CaptureAtMs)
    waitForIdle()
    mainClock.autoAdvance = true
    when (shot) {
        ApprovalShot.QuestionValidation -> {
            onNodeWithText("Postgres").performClick()
            onNodeWithTag("question-next").performClick()
            waitForIdle()
            onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-submit"))
            onNodeWithTag("question-submit").performClick()
        }
        ApprovalShot.Denials -> {
            // Open both activity groups (their denials sit inside them, as on the web), one at a time.
            val closed = hasTestTag("tool-activity-group") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
            repeat(2) {
                onNodeWithTag("chat-transcript").performScrollToNode(closed)
                onAllNodes(closed)[0].performClick()
                waitForIdle()
            }
            onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("denial-origin-link"))
        }
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

@androidx.compose.runtime.Composable
private fun CompositionLocalProviderForMedia(content: @androidx.compose.runtime.Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalToolMediaLoader provides ToolFixtures.FakeLoader(), content = content)

/** Every attention-card state × all 6 skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ApprovalCardsPhoneScreenshotTest(private val shot: ApprovalShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun cards() = rule.snapApproval(shot, skin, shot.id, "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = ApprovalShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** The web's desktop layout: the approval and question cards span the column, a denial is 94% wide. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ApprovalCardsTabletScreenshotTest(private val shot: ApprovalShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun cards() = rule.snapApproval(shot, skin, shot.id, "tablet", WellHeightTablet, WellWidthTablet)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ApprovalShot.Write, ApprovalShot.Question, ApprovalShot.Denials).flatMap { s ->
            TetherSkin.entries.map { arrayOf<Any>(s, it) }
        }
    }
}

/** PLAN §4: 1.3× font scale does not break the cards (Machine + Studio). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ApprovalCardsFontScaleScreenshotTest(private val shot: ApprovalShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun cards() = rule.snapApproval(shot, skin, "${shot.id}-font-1.3x", "phone", WellHeightPhone)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(ApprovalShot.Grants, ApprovalShot.Question, ApprovalShot.Denials).flatMap { s ->
            listOf(TetherSkin.Machine, TetherSkin.Studio).map { arrayOf<Any>(s, it) }
        }
    }
}
