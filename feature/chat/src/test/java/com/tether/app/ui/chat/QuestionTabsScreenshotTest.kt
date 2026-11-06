package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ConsentResult
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AW.2.9 goldens (lane A-tabs): the 4-question card at 360dp, Studio + StudioDark. The store is seeded with the state
 * each shot names (the slot of a prompt is its index: the four questions have four texts).
 */
enum class QuestionTabsShot(val id: String) {
    FirstPass("question-tabs-first-pass"),
    SkippedUndo("question-tabs-skipped-undo"),
    Last("question-tabs-last"),
    FailedSubmit("question-tabs-failed-submit"),
    Revisit("question-tabs-revisit"),
}

private fun seed(shot: QuestionTabsShot): QuestionSelection = when (shot) {
    // Q1 answered, Q2 selected, Next disabled.
    QuestionTabsShot.FirstPass -> QuestionSelection(page = 1, picks = mapOf(0 to listOf(0)))
    // Q3 skipped after a pick: the note and Undo skip, Skip disabled.
    QuestionTabsShot.SkippedUndo -> QuestionSelection(
        page = 2,
        picks = mapOf(0 to listOf(0), 1 to listOf(1)),
        skipped = setOf(2),
        skipUndo = mapOf(2 to SkippedAnswer(listOf(0), "")),
    )
    // Q4 selected, everything settled.
    QuestionTabsShot.Last -> QuestionSelection(page = 3, picks = mapOf(0 to listOf(0), 1 to listOf(1), 3 to listOf(0)), skipped = setOf(2))
    // Submit was tried on Q4 with Q2 open: Q2 is selected, every unsettled tab shows the alert.
    QuestionTabsShot.FailedSubmit -> QuestionSelection(page = 1, picks = mapOf(0 to listOf(0), 2 to listOf(1), 3 to listOf(0)), attempted = true)
    // Q2 selected, everything settled.
    QuestionTabsShot.Revisit -> QuestionSelection(page = 1, picks = mapOf(0 to listOf(0), 1 to listOf(1), 3 to listOf(1)), skipped = setOf(2))
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapTabs(shot: QuestionTabsShot, skin: TetherSkin, name: String) {
    mainClock.autoAdvance = false
    val fixture = ApprovalFixtures.questionTabs
    val store = CardStateStore()
    store.setQuestion(pendingQuestions(fixture.tree).single().contentFp, seed(shot))
    val listState = LazyListState()
    val consent = ConsentActions(
        sessionId = "s1",
        origin = TEST_ORIGIN,
        lock = null,
        decided = emptySet(),
        questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.Sent },
        onOpenRun = {},
    )
    setContent {
        ChatHost(skin, wellHeight = 800.dp) {
            CompositionLocalProvider(LocalCardStates provides store) {
                ChatTranscript(
                    projection = fixture.projection, tree = fixture.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, listState = listState, consent = consent,
                )
            }
        }
    }
    mainClock.advanceTimeBy(600L)
    waitForIdle()
    mainClock.autoAdvance = true
    onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-submit").or(hasTestTag("question-next")))
    waitForIdle()
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(600L)
    waitForIdle()
    onNodeWithTag(WellTag).captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-phone360.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi")
class QuestionTabsScreenshotTest(private val shot: QuestionTabsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun card() = rule.snapTabs(shot, skin, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = QuestionTabsShot.entries.flatMap { s -> listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 1.3f)
class QuestionTabsFontScaleScreenshotTest(private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun card() = rule.snapTabs(QuestionTabsShot.FirstPass, skin, "question-tabs-first-pass-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(TetherSkin.Studio, TetherSkin.StudioDark).map { arrayOf<Any>(it) }
    }
}
