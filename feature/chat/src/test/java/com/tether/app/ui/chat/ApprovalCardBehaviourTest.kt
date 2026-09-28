package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.ConsentResult
import com.tether.app.client.consentKey
import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.3 behaviour (SYNC_DESIGN §5.1 I2/I3 at the UI): a decision leaves a card only from a tap, once,
 * for the request the card shows, with a choice that request offered; a locked, decided or
 * answered request is shown but not actionable, and says why in words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ApprovalCardBehaviourTest {
    @get:Rule val rule = createComposeRule()

    /** Every call a card made (not de-duplicated) and what the "client" answered. */
    private val calls = mutableListOf<String>()
    private var verdict = ConsentResult.Sent

    private fun actions(lock: ConsentLock? = null, decided: Set<String> = emptySet(), questionUnavailable: String? = null) = ConsentActions(
        sessionId = "s1",
        lock = lock,
        decided = decided,
        questionUnavailable = questionUnavailable,
        onApproval = { requestId, choiceId, decision, granted ->
            calls += "approval:$requestId:${choiceId ?: decision}" + (granted?.let { ":" + it.toJsonObject() } ?: "")
            verdict
        },
        onAnswer = { requestId, answers, response ->
            calls += "question:$requestId:$answers" + (response?.let { ":$it" } ?: "")
            verdict
        },
        onOpenRun = { calls += "open-run:$it" },
    )

    private var fixture by mutableStateOf(ApprovalFixtures.write)
    private var consent by mutableStateOf(ConsentActions.Unavailable)
    /** Bumped to throw the whole transcript away and build it again (a fresh card, fresh state). */
    private var generation by mutableStateOf(0)
    private var hosted = false

    private fun show(f: ChatFixtures.Folded, c: ConsentActions = actions()) {
        if (hosted) {
            rule.runOnIdle {
                fixture = f
                consent = c
                generation++
            }
            rule.waitForIdle()
            return
        }
        hosted = true
        fixture = f
        consent = c
        rule.setContent {
            ChatHost(TetherSkin.Machine, wellHeight = 900.dp) {
                androidx.compose.runtime.key(generation) {
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        consent = consent,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    @Test fun nothingIsSentWithoutATapWhateverArrives() {
        show(ApprovalFixtures.write)
        // A new request, a re-render, a recomposition: none of them is a tap.
        val next = foldTree(fixture.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-2"); put("toolId", "t-2"); put("name", "Bash"); putJsonObject("input") { put("command", "ls") }
        })
        rule.runOnIdle { fixture = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(next)!!, next) }
        rule.waitForIdle()
        rule.runOnIdle { consent = actions() }
        rule.waitForIdle()
        rule.onAllNodesWithTag("approval-card").assertCountEquals(2)
        assertTrue(calls.isEmpty())
    }

    @Test fun oneTapSendsOneDecisionForTheRequestItShows() {
        show(ApprovalFixtures.write)
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-w:allow"), calls)
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
        rule.onNodeWithTag("approval-deny").assertIsNotEnabled()
        rule.onNodeWithTag("approval-deny").performClick()
        rule.onNodeWithTag("consent-sent").assertIsDisplayed()
        assertEquals(1, calls.size)
    }

    @Test fun aDoubleTapInsideOneFrameSendsOnce() {
        show(ApprovalFixtures.write)
        scrollTo("approval-allow")
        val node = rule.onNodeWithTag("approval-allow").fetchSemanticsNode()
        val click = node.config[SemanticsActions.OnClick].action!!
        // Two taps before any recomposition could disable the key.
        rule.runOnUiThread {
            click()
            click()
        }
        rule.waitForIdle()
        assertEquals(listOf("approval:req-w:allow"), calls)
    }

    @Test fun aDecisionSurvivesTheCardBeingRecreated() {
        show(ApprovalFixtures.write)
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        // The client's ledger now lists it: a fresh card for the same request (another tab, a
        // re-created list) renders it decided and cannot send.
        show(ApprovalFixtures.write, actions(decided = setOf(consentKey("s1", "req-w"))))
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
        rule.onNodeWithTag("consent-sent").assertIsDisplayed()
        assertEquals(1, calls.size)
    }

    @Test fun aRefusedSendLeavesTheCardAnswerable() {
        verdict = ConsentResult.NotConnected
        show(ApprovalFixtures.write)
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        // Nothing went out, so nothing is settled (use-tether.ts: send() returned false).
        rule.onNodeWithTag("approval-allow").assertIsEnabled()
        verdict = ConsentResult.Sent
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-w:allow", "approval:req-w:allow"), calls)
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
    }

    @Test fun everyLockDisablesTheCardsAndSaysWhy() {
        for (lock in ConsentLock.entries) {
            calls.clear()
            show(ApprovalFixtures.write, actions(lock = lock))
            scrollTo("approval-allow")
            rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
            rule.onNodeWithTag("approval-deny").assertIsNotEnabled()
            rule.onNodeWithText(lock.copy).assertIsDisplayed()
            rule.onNodeWithTag("approval-allow").performClick()

            show(ApprovalFixtures.question, actions(lock = lock))
            scrollTo("question-submit")
            rule.onNodeWithTag("question-submit").assertIsNotEnabled().assert(hasText("ANSWER UNAVAILABLE", ignoreCase = true))
            rule.onAllNodesWithTag("question-option").onFirst().assertIsNotEnabled()
            rule.onNodeWithText(lock.copy).assertIsDisplayed()
            assertTrue("$lock sent something", calls.isEmpty())
        }
    }

    @Test fun providerChoicesSendTheirOwnIds() {
        show(ApprovalFixtures.choices)
        scrollTo("approval-choice")
        rule.onNodeWithText("ALLOW FOR THIS SESSION", ignoreCase = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-c:acceptForSession"), calls)
        // The description the web shows on hover is spoken with the label.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Allow for this session. Until the session ends")))
            .assertIsNotEnabled()
    }

    @Test fun eachCardIsBoundToItsOwnRequest() {
        val two = foldTree(ApprovalFixtures.write.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-2"); put("toolId", "t-2"); put("name", "Bash"); putJsonObject("input") { put("command", "ls") }
        })
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(two)!!, two))
        scrollTo("approval-allow")
        rule.onAllNodesWithTag("approval-deny")[1].performClick()
        rule.onAllNodesWithTag("approval-allow")[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-2:deny", "approval:req-w:allow"), calls)
    }

    @Test fun anExactGrantNeedsTheConfirmationAndASubsetNeedsATick() {
        show(ApprovalFixtures.grants)
        scrollTo("approval-choice")
        val all = rule.onNodeWithText("ALLOW ALL", ignoreCase = true)
        val some = rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true)
        all.assertIsNotEnabled()
        some.assertIsEnabled() // everything requested starts ticked
        rule.onAllNodesWithTag("grant-read")[0].assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        // Untick everything: a subset of nothing is not a grant.
        rule.onAllNodesWithTag("grant-read")[0].performClick()
        rule.onAllNodesWithTag("grant-read")[1].performClick()
        rule.onNodeWithTag("grant-write").performClick()
        rule.onNodeWithTag("grant-network").performClick()
        rule.onNodeWithTag("grant-network").assertIsOff()
        some.assertIsNotEnabled()
        rule.onAllNodesWithTag("grant-read")[1].performClick()
        some.performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql")).toJsonObject()), calls)
    }

    @Test fun theExactGrantSendsTheRequestedExpansionAfterTheConfirmation() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("""approval:req-g:all:{"fileSystem":{"read":["/srv/fixtures","/srv/schema.sql"],"write":["/w/report"]},"network":{"enabled":true}}"""),
            calls,
        )
    }

    @Test fun aQuestionPagesAndSubmitsOnceWithTheWebsPayload() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithTag("question-page").assert(hasText("Question 1 of 2"))
        rule.onNodeWithTag("question-next").assertIsNotEnabled()
        rule.onNodeWithText("Postgres").performClick()
        rule.onAllNodesWithTag("question-option")[0].assertIsOn()
        rule.onNodeWithTag("question-next").assertIsEnabled().performClick()
        rule.waitForIdle()
        scrollTo("question-submit")
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        rule.onNodeWithText("staging").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("question-other"))).performTextInput("  canary ")
        val submit = rule.onNodeWithTag("question-submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            submit()
            submit()
        }
        rule.waitForIdle()
        assertEquals(listOf("question:q-1:{${ApprovalFixtures.Q_DB}=Postgres, ${ApprovalFixtures.Q_ENV}=staging, canary}:canary"), calls)
        rule.onNodeWithTag("question-submit").assertIsNotEnabled().assert(hasText("ANSWER SENT", ignoreCase = true))
    }

    @Test fun submittingWithAnUnansweredPageAsksForAnAnswerOrASkip() {
        show(ApprovalFixtures.question)
        scrollTo("question-skip")
        rule.onNodeWithTag("question-skip").assertHeightIsAtLeast(44.dp).performClick() // skip page 1
        rule.waitForIdle()
        scrollTo("question-submit")
        rule.onNodeWithTag("question-submit").performClick() // page 2 unanswered
        rule.waitForIdle()
        rule.onNodeWithTag("question-validation").assertIsDisplayed()
        assertTrue(calls.isEmpty())
        rule.onNodeWithTag("question-skip").performClick() // skip page 2 = submit with nothing answered
        rule.waitForIdle()
        assertEquals(listOf("question:q-1:{}"), calls)
    }

    @Test fun anAnsweredOrLegacyOpencodeQuestionCannotBeAnswered() {
        val tree = foldTree(ApprovalFixtures.question.tree, ev("question_answered", "t1", ts = 1) { put("requestId", "q-1"); put("toolId", "ask-1") })
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        scrollTo("question-submit")
        rule.onNodeWithText("Already answered.").assertIsDisplayed()
        rule.onNodeWithTag("question-submit").assertIsNotEnabled()

        show(ApprovalFixtures.question, actions(questionUnavailable = ConsentActions.LEGACY_OPENCODE_QUESTION))
        scrollTo("question-submit")
        rule.onNodeWithText(ConsentActions.LEGACY_OPENCODE_QUESTION).assertIsDisplayed()
        rule.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test fun targetsAreReachableAndNamed() {
        show(ApprovalFixtures.question)
        scrollTo("question-option")
        rule.onAllNodesWithTag("question-option")[0]
            .assertHeightIsAtLeast(44.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        show(ApprovalFixtures.grants)
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertHeightIsAtLeast(44.dp)
        rule.onNodeWithTag("approval-card").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Tool approval required"))
    }

    @Test fun theDenialOriginLinkOpensItsRun() {
        show(ApprovalFixtures.denials)
        // Open both activity groups (their denials sit inside, as on the web).
        rule.onAllNodesWithTag("tool-activity-group")[1].performClick()
        rule.onAllNodesWithTag("tool-activity-group")[0].performClick()
        rule.waitForIdle()
        scrollTo("denial-origin-link")
        rule.onNodeWithTag("denial-origin-link").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(listOf("open-run:t1::task-1"), calls)
    }
}

/**
 * T6.3 on the chat screen: the lock follows the client's link and live set; the session tab and a
 * sub-agent tab show the SAME pending card, and a decision on one leaves the other decided.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ApprovalScreenBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val session = chatSession("s1", historyId = null)

    /** A running Agent run plus a pending approval in the same active turn. */
    private val withRun: ChatFixtures.Folded by lazy {
        val tree = foldTree(
            ApprovalFixtures.write.tree,
            ev("tool_start", "t1", ts = 1) { put("toolId", "task-9"); put("name", "Agent"); putJsonObject("input") { put("description", "Survey") } },
        )
        ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    private fun host(client: ChatTestClient): TetherViewModel {
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    @Test fun offlineAndCatchingUpCopiesAreNotActionable() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = false)
        client.link.value = ConnectionState.Disconnected
        host(client)
        scrollTo("approval-allow")
        rule.onNodeWithText(ConsentLock.Offline.copy).assertIsDisplayed()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled().performClick()

        rule.runOnIdle { client.link.value = ConnectionState.Connected }
        rule.waitForIdle()
        rule.onNodeWithText(ConsentLock.CatchingUp.copy).assertIsDisplayed()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()

        rule.runOnIdle { client.live.value = setOf("s1") }
        rule.waitForIdle()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    @Test fun theSameRequestOnTwoTabsIsDecidedOnce() {
        val client = ChatTestClient()
        client.show(session, withRun)
        val vm = host(client)
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        rule.runOnIdle { vm.selectRun("s1", "t1::task-9") }
        rule.waitForIdle()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("consent-sent").assertIsDisplayed()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    @Test fun aReadOnlySessionShowsItsLock() {
        val client = ChatTestClient()
        client.show(session.copy(readOnly = true), ApprovalFixtures.question)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Machine)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session.copy(readOnly = true), projection = projections["s1"], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        scrollTo("question-submit")
        rule.onNodeWithText(ConsentLock.ReadOnly.copy).assertIsDisplayed()
        rule.onNodeWithTag("question-submit").assertIsNotEnabled()
        assertTrue(client.consentCalls.isEmpty())
    }
}
