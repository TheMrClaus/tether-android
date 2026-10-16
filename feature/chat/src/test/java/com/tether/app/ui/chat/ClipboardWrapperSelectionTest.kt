package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.ui.text.SafeCopyClipboard
import com.tether.app.ui.theme.TetherSkin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-esj0 (1.3.0 crash): a long press in a text field under a wrapped clipboard threw
 * "Extracting native reference is only supported from AndroidClipboard instances" when the
 * wrapper was not itself an AndroidClipboard. The question card's "Other" box sits inside the
 * transcript row's [SafeCopyClipboard]; a long press there must open the selection toolbar path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h2400dp-420dpi", shadows = [NoMagnifier::class])
class ClipboardWrapperSelectionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aLongPressInTheQuestionCardsOtherBoxDoesNotCrash() {
        val fixture = ApprovalFixtures.question
        val consent = ConsentActions(
            sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
            onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
            onAnswer = { _, _, _, _ -> ConsentResult.Sent },
            onOpenRun = {},
        )
        val store = CardStateStore()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                CompositionLocalProvider(LocalCardStates provides store) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false,
                        onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = consent,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-next"))
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val field = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("question-other")))
        field.performTextInput("some words to select")
        rule.waitForIdle()
        field.performTouchInput { longClick(center) }
        rule.waitForIdle()
        // ta-bp5u r2: the field is NOT inside the card's selection container (the one holding SafeCopyClipboard), so the
        // long press above never reached the wrapper; the card's words are.
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("question-words")), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("The agent needs your input", useUnmergedTree = true).assertExists()
    }

    /** ta-bp5u r2: one selection container (one SelectableRow) holds title, header and question, so one drag spans them. */
    @Test fun theTitleHeaderAndQuestionShareOneSelectionRegionAndTheOptionsAndFieldDoNot() {
        val fixture = ApprovalFixtures.question
        val consent = ConsentActions(
            sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
            onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
            onAnswer = { _, _, _, _ -> ConsentResult.Sent },
            onOpenRun = {},
        )
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                CompositionLocalProvider(LocalCardStates provides CardStateStore()) {
                    ChatTranscript(
                        projection = fixture.projection, tree = fixture.tree, showThinking = false,
                        onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = consent,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-next"))
        val words = hasAnyAncestor(hasTestTag("question-words"))
        rule.onNode(hasText("The agent needs your input") and words, useUnmergedTree = true).assertExists()
        rule.onNode(hasText(ApprovalFixtures.Q_DB) and words, useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Postgres") and words, useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasTestTag("question-other") and words, useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasTestTag("question-other"), useUnmergedTree = true).assertExists()
    }

    @Test fun aLongPressInAnyTextFieldUnderTheSafeCopyClipboardDoesNotCrash() {
        rule.setContent {
            val base = LocalClipboard.current
            val safe = remember(base) { SafeCopyClipboard(base, null) }
            CompositionLocalProvider(LocalClipboard provides safe) {
                var text by remember { mutableStateOf("some words to select") }
                BasicTextField(text, { text = it }, Modifier.testTag("plain-field"))
            }
        }
        rule.onNodeWithTag("plain-field").performTouchInput { longClick(center) }
        rule.waitForIdle()
    }
}
