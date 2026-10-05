package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.tether.app.ui.theme.TetherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.5 slice c, against tether components/chat-view.tsx 90fbb9f: the `@` picker's "Sessions on this
 * project" (:2791-2802 — same cwd, not self, not handed off, not archived, newest first; running /
 * waiting rows locked, :3972-4011), picking one (beginHandoff :2834-2851: the brief is asked for, the
 * `@` token stripped, "Preparing takeover brief…"), the brief seeding the editable text once
 * (:2911-2924), Take over (commitHandoff :2935-2950: `handoff` with the edited text) and Cancel
 * (:2925-2929: the brief dropped, nothing sent).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TakeoverComposerBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rec = TakeoverFixtures.Recorder()
    private val sends = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            ComposerHost(TetherSkin.StudioDark) {
                Composer(
                    session = ComposerFixtures.session,
                    projection = ComposerFixtures.idle.projection,
                    controls = null,
                    serverNow = { ComposerFixtures.BUSY_NOW },
                    onSend = { text, _ -> sends += text; true },
                    onInterrupt = { com.tether.app.client.InterruptResult.Sent },
                    onQueueEdit = { _, _ -> },
                    onQueueRemove = {},
                    onRequestControls = {},
                    liveness = ComposerLiveness.Live,
                    tree = ComposerFixtures.idle.tree,
                    runActions = CommandFixtures.Recorder().actions(),
                    takeover = rec.takeover(),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun input() = rule.onNodeWithContentDescription("Message the agent")
    private fun inputText(): String = input().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun editorText(): String =
        rule.onNodeWithTag(TakeoverTags.Editor).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }
    private fun sessionRowTags(): List<String> =
        rule.onAllNodes(SemanticsMatcher("a session row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("mention-session-") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.TestTag] }

    @Test
    fun theSessionsSectionListsThisProjectsSessionsNewestFirst() {
        show()
        input().performTextInput("Continue @")
        rule.waitForIdle()
        rule.onNodeWithTag(MENTION_MENU_TAG).assertExists()
        rule.onNodeWithContentDescription("Sessions on this project").assertExists()
        // Not self, not another folder, not handed off, not archived; newest message first.
        assertEquals(
            listOf("s-run", "s-wait", "s-new", "s-old").map(TakeoverTags::session),
            sessionRowTags(),
        )
        // The query filters by name or provider.
        input().performTextInput("codex")
        rule.waitForIdle()
        assertEquals(listOf("s-run", "s-new").map(TakeoverTags::session), sessionRowTags())
        input().performTextReplacement("Continue @PARSER")
        rule.waitForIdle()
        assertEquals(listOf("s-new").map(TakeoverTags::session), sessionRowTags())
    }

    @Test
    fun aRunningOrWaitingSessionIsLockedAndSaysWhy() {
        show()
        input().performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag(TakeoverTags.session("s-run"), useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithTag(TakeoverTags.session("s-wait"), useUnmergedTree = true).assertIsNotEnabled()
        val running = rule.onNodeWithTag(TakeoverTags.session("s-run"), useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue(running.getOrNull(SemanticsProperties.ContentDescription)!!.single().endsWith("interrupt first"))
        // Enter takes the first row; a running one is refused in words, nothing is asked for.
        input().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithText("“Write the migration” is running — interrupt it first, then take over.").assertExists()
        assertEquals(emptyList<String>(), rec.briefRequests)
        rule.onAllNodesWithTag(TakeoverTags.Draft).assertCountEquals(0)
    }

    @Test
    fun pickingASessionAsksForItsBriefAndTakeOverSendsTheEditedText() {
        show()
        input().performTextInput("Pick up @fix")
        rule.waitForIdle()
        tap(TakeoverTags.session("s-old"))
        assertEquals(listOf("s-old"), rec.briefRequests)
        // The `@fix` token is stripped; the menu closes; the draft waits for the brief.
        assertEquals("Pick up", inputText())
        rule.onAllNodesWithTag(MENTION_MENU_TAG).assertCountEquals(0)
        rule.onNodeWithTag(TakeoverTags.Draft).assertExists()
        rule.onNodeWithText("Take over “Fix the login flow”").assertExists()
        rule.onNodeWithText("Preparing takeover brief…").assertExists()
        rule.onAllNodesWithTag(TakeoverTags.Commit).assertCountEquals(0)
        // While a takeover is open the `@` picker stays shut.
        input().performTextInput(" @")
        rule.waitForIdle()
        rule.onAllNodesWithTag(MENTION_MENU_TAG).assertCountEquals(0)

        // The brief lands: the instruction seeds the editor, the digest is folded away.
        rec.briefs = mapOf("s-old" to TakeoverFixtures.brief())
        rule.waitForIdle()
        rule.onAllNodesWithTag(TakeoverTags.Loading).assertCountEquals(0)
        assertEquals(TakeoverFixtures.INSTRUCTION, editorText())
        rule.onAllNodesWithTag(TakeoverTags.Summary).assertCountEquals(0)
        tap(TakeoverTags.SummaryToggle)
        rule.onNodeWithTag(TakeoverTags.Summary).assertExists()

        // Edited, then committed: `handoff` with the trimmed edited text; the draft closes.
        rule.onNodeWithTag(TakeoverTags.Editor).performTextReplacement("  Carry on with the login fix.  ")
        rule.waitForIdle()
        tap(TakeoverTags.Commit)
        assertEquals(listOf("s-old" to "Carry on with the login fix."), rec.handoffs)
        assertEquals(listOf("s-old"), rec.cleared)
        rule.onAllNodesWithTag(TakeoverTags.Draft).assertCountEquals(0)
        assertEquals("nothing went as a plain send", emptyList<String>(), sends)
    }

    @Test
    fun anEmptyBriefCannotBeCommittedAndAFailedSendSaysSo() {
        show()
        input().performTextInput("@")
        rule.waitForIdle()
        tap(TakeoverTags.session("s-new"))
        rec.briefs = mapOf("s-new" to TakeoverFixtures.brief("s-new"))
        rule.waitForIdle()
        rule.onNodeWithTag(TakeoverTags.Editor).performTextReplacement("   ")
        rule.waitForIdle()
        rule.onNodeWithTag(TakeoverTags.Commit, useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithTag(TakeoverTags.Editor).performTextReplacement("Go")
        rule.waitForIdle()
        rule.onNodeWithTag(TakeoverTags.Commit, useUnmergedTree = true).assertIsEnabled()
        rec.handoffResult = false
        tap(TakeoverTags.Commit)
        rule.onNodeWithText("Not connected — reconnecting. Try the takeover again in a moment.").assertExists()
        // The draft stays, with its text, for another try.
        rule.onNodeWithTag(TakeoverTags.Draft).assertExists()
        assertEquals("Go", editorText())
    }

    @Test
    fun cancelDropsTheBriefAndSendsNothing() {
        show()
        input().performTextInput("@")
        rule.waitForIdle()
        tap(TakeoverTags.session("s-old"))
        rec.briefs = mapOf("s-old" to TakeoverFixtures.brief())
        rule.waitForIdle()
        tap(TakeoverTags.Cancel)
        assertEquals(listOf("s-old"), rec.cleared)
        assertEquals(emptyList<Pair<String, String>>(), rec.handoffs)
        rule.onAllNodesWithTag(TakeoverTags.Draft).assertCountEquals(0)
        // The X ("Cancel takeover") does the same, and the `@` picker is offered again.
        input().performTextInput(" @")
        rule.waitForIdle()
        tap(TakeoverTags.session("s-new"))
        tap(TakeoverTags.CancelX)
        assertEquals(listOf("s-old", "s-new"), rec.cleared)
        rule.onAllNodesWithTag(TakeoverTags.Draft).assertCountEquals(0)
    }
}
