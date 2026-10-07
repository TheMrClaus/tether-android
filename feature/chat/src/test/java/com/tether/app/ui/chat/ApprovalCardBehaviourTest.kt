package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
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

    /** The fingerprints the cards sent with each call, in order. */
    private val fingerprints = mutableListOf<String>()

    private fun actions(
        lock: ConsentLock? = null,
        decided: Set<String> = emptySet(),
        questionUnavailable: String? = null,
        unconfirmed: Set<String> = emptySet(),
    ) = ConsentActions(
        sessionId = "s1",
        origin = TEST_ORIGIN,
        lock = lock,
        decided = decided,
        questionUnavailable = questionUnavailable,
        onApproval = { requestId, fingerprint, choiceId, decision, granted ->
            calls += "approval:$requestId:${choiceId ?: decision}" + (granted?.let { ":" + it.toJsonObject() } ?: "")
            fingerprints += fingerprint
            verdict
        },
        onAnswer = { requestId, fingerprint, picks, skipped ->
            // The guard builds the answer from the request; record what it would send.
            val request = com.tether.app.client.ConsentGuard.pendingQuestion(fixture.tree, requestId)
            val reply = request?.let { com.tether.app.client.ConsentGuard.buildAnswers(it, picks, skipped) }
            calls += "question:$requestId:" + (reply?.let { "${it.answers}" + (it.response?.let { r -> ":$r" } ?: "") } ?: "<invalid $picks>")
            fingerprints += fingerprint
            verdict
        },
        onOpenRun = { calls += "open-run:$it" },
        unconfirmed = unconfirmed,
    )

    /** The fingerprint the card renders for [requestId] of [f]. */
    private fun fpOf(f: ChatFixtures.Folded, requestId: String): String =
        (pendingApprovals(f.tree).map { it.requestId to wireFingerprint(TEST_ORIGIN, it.activeTurnId, it.request) } +
            pendingQuestions(f.tree).map { it.requestId to wireFingerprint(TEST_ORIGIN, it.activeTurnId, it.request) })
            .first { it.first == requestId }.second

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
            arm()
            return
        }
        hosted = true
        fixture = f
        consent = c
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                androidx.compose.runtime.CompositionLocalProvider(LocalCardStates provides store) {
                androidx.compose.runtime.key(generation) {
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        consent = consent,
                        listState = listState,
                    )
                }
                }
            }
            hostView = androidx.compose.ui.platform.LocalView.current
        }
        rule.waitForIdle()
        arm()
    }

    private val listState = androidx.compose.foundation.lazy.LazyListState()

    /** The card store the screen would provide (tests reach in to simulate a lost record). */
    private val store = CardStateStore()
    private var hostView: android.view.View? = null

    /** Let the composition settle ([SETTLE_MS]); ta-coik.13: the cards have no arm delay to wait out. */
    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
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
        show(ApprovalFixtures.write, actions(decided = setOf(consentKey("s1", "req-w", fpOf(ApprovalFixtures.write, "req-w")))))
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

    /** chat-view.tsx 90fbb9f :1191-1205, 1270-1289 (ta-coik.5): "exact" needs its box; "subset" needs only a tick. */
    @Test fun anExactGrantNeedsTheConfirmationAndASubsetNeedsATick() {
        show(ApprovalFixtures.grants)
        scrollTo("approval-choice")
        val all = rule.onNodeWithText("ALLOW ALL", ignoreCase = true)
        val some = rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true)
        all.assertIsNotEnabled()
        // Everything requested starts ticked: Allow selected works at once, as on the web.
        some.assertIsEnabled()
        rule.onAllNodesWithTag("grant-read")[0].assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        // Untick everything: a subset of nothing is not a grant.
        rule.onAllNodesWithTag("grant-read")[0].performClick()
        rule.onAllNodesWithTag("grant-read")[1].performClick()
        rule.onNodeWithTag("grant-write").performClick()
        rule.onNodeWithTag("grant-network").performClick()
        rule.onNodeWithTag("grant-network").assertIsOff()
        some.assertIsNotEnabled()
        rule.onAllNodesWithTag("grant-read")[1].performClick()
        // One tick: the first tap on Allow selected sends it, no confirmation.
        some.assertIsEnabled().performClick()
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
        arm()
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
        arm()
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

    // ---- round 2 -------------------------------------------------------------------------------

    /** 25 finished turns, then the grants request: the card can scroll far out of the lazy list. */
    private val grantsAfterHistory: ChatFixtures.Folded by lazy {
        val history = (1..25).flatMap { n -> ChatFixtures.turn("h$n", "Prompt $n", "Reply $n", ApprovalFixtures.T).toList() }
        val tree = foldTree(com.tether.app.protocol.reduce.foldTree(com.tether.app.protocol.reduce.freshTree(), *history.toTypedArray()), *grantsEvents())
        ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    /** The grants fixture's events (re-folded on top of the history). */
    private fun grantsEvents(): Array<com.tether.app.protocol.AgentEvent> {
        val grants = ApprovalFixtures.grants.tree
        val approval = ((((grants["turnsById"] as com.tether.app.protocol.tree.JsObj)["t1"] as com.tether.app.protocol.tree.JsObj)["pendingApprovals"]
            as com.tether.app.protocol.tree.JsObj)["req-g"] as com.tether.app.protocol.tree.JsObj)
        val raw = com.tether.app.protocol.tree.JsCodec.toJson(approval) as kotlinx.serialization.json.JsonObject
        return arrayOf(
            ev("turn_started", "g1", ts = 1) { put("idempotencyKey", "k-g1") },
            ev("approval_request", "g1", ts = 1) { raw.forEach { (k, v) -> put(k, v) } },
        )
    }

    private val narrowed = "approval:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = listOf("/w/report")).toJsonObject()

    /** ta-coik.5: Allow selected on the first tap (no confirmation, as on the web). */
    private fun confirmAndAllowSelected() {
        scrollTo("approval-choice")
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
    }

    private fun narrowTheGrant() {
        scrollTo("grant-network")
        rule.onAllNodesWithTag("grant-read")[0].performClick() // untick /srv/fixtures
        rule.onNodeWithTag("grant-network").performClick() // untick network
        rule.waitForIdle()
    }

    @Test fun aNarrowedGrantSurvivesScrollingTheCardAwayAndBack() {
        show(grantsAfterHistory)
        narrowTheGrant()
        rule.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(0) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("approval-card").assertCountEquals(0) // disposed with its lazy row
        scrollTo("approval-choice")
        arm()
        rule.onAllNodesWithTag("grant-read")[0].assertIsOff()
        rule.onNodeWithTag("grant-network").assertIsOff()
        confirmAndAllowSelected()
        assertEquals(listOf(narrowed), calls)
    }

    @Test fun aNarrowedGrantSurvivesStateRestoration() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        val c = actions()
        tester.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(projection = ApprovalFixtures.grants.projection, tree = ApprovalFixtures.grants.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c)
            }
        }
        rule.waitForIdle()
        arm()
        narrowTheGrant()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        arm()
        scrollTo("approval-choice")
        rule.onNodeWithTag("grant-network").assertIsOff()
        confirmAndAllowSelected()
        assertEquals(listOf(narrowed), calls)
    }

    @Test fun afterProcessDeathTheOperatorMayDecideAgain() {
        // L3: no saved "sent" flag. The ledger (here: none) is the only memory of a decision, and it
        // dies with the process, so a restored card is answerable again (SYNC_DESIGN §5.4).
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        val c = actions()
        tester.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(projection = ApprovalFixtures.write.projection, tree = ApprovalFixtures.write.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c)
            }
        }
        rule.waitForIdle()
        arm()
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        arm()
        scrollTo("approval-allow")
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-w:allow", "approval:req-w:allow"), calls)
    }

    @Test fun aReRaisedRequestStartsOverWithItsNewFingerprint() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        val before = fpOf(ApprovalFixtures.grants, "req-g")
        // Same id, a WIDER request (a new write path).
        val wider = foldTree(ApprovalFixtures.grants.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-g"); put("toolId", "perm-1"); put("name", "permissions")
            putJsonArray("choices") {
                addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
            }
            putJsonObject("metadata") {
                put("provider", "codex"); put("kind", "permissions")
                putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("write") { add("/w/report"); add("/etc") } } }
            }
        })
        val next = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(wider)!!, wider)
        rule.runOnIdle { fixture = next }
        rule.waitForIdle()
        val after = fpOf(next, "req-g")
        assertTrue(before != after)
        scrollTo("grant-confirm")
        // The earlier confirmation belonged to the earlier request.
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsNotEnabled()
        arm()
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf(after), fingerprints)
    }

    @Test fun aCardActsOnTheFirstTapTheMomentItAppears() {
        // ta-coik.13: chat-view.tsx 90fbb9f :1291-1326, the web's Approve / Deny / choice buttons act
        // on the first click: no arm delay. The tap lands in the card's first composed frame.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                androidx.compose.runtime.CompositionLocalProvider(LocalCardStates provides store) {
                    ChatTranscript(
                        projection = ApprovalFixtures.write.projection,
                        tree = ApprovalFixtures.write.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        consent = actions(),
                        listState = listState,
                    )
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf("approval:req-w:allow"), calls)
    }

    @Test fun aPressAcrossAReRaiseOfTheRequestIsDropped() {
        // ta-coik.13's stale-tap guard: the request was re-raised (same id, wider) while the finger
        // was down; the press began on the old request's key and never decides the new one.
        show(grantsAfterHistory)
        scrollTo("approval-choice")
        rule.pressAcross({ rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true) }) { fixture = widerGrants(grantsAfterHistory, "g1") }
        assertTrue("a press on the old request decided the new one: $calls", calls.isEmpty())
        // A fresh tap on the new request acts at once.
        scrollTo("approval-choice")
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, calls.size)
    }

    @Test fun aPressOnAQuestionPageThatChangedUnderTheFingerIsDropped() {
        // ta-coik.13's stale-tap guard: page 1's Skip was pressed, page 2 came up under the finger
        // (another tap moved the store's page); the lift never skips page 2.
        show(ApprovalFixtures.question)
        scrollTo("question-skip")
        val cfp = pendingQuestions(ApprovalFixtures.question.tree).single().contentFp
        rule.pressAcross({ rule.onNodeWithTag("question-skip") }) { store.setQuestion(cfp, store.question(cfp).copy(page = 1)) }
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        assertTrue("page 2 was skipped by a press on page 1: ${store.question(cfp).skipped}", store.question(cfp).skipped.isEmpty())
        assertTrue(calls.isEmpty())
    }

    /** Tap [tag]'s centre with MotionEvents carrying [flags], straight into the host view. */
    private fun tapWithFlags(tag: String, flags: Int) {
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val x = bounds.center.x
        val y = bounds.center.y
        val view = checkNotNull(hostView)
        rule.runOnUiThread {
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val e = android.view.MotionEvent.obtain(0L, 10L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, flags)
                view.dispatchTouchEvent(e)
                e.recycle()
            }
        }
        rule.waitForIdle()
    }

    @Test fun aTouchThroughAnOverlayIsRefused() {
        show(ApprovalFixtures.write)
        scrollTo("approval-allow")
        tapWithFlags("approval-allow", android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        tapWithFlags("approval-deny", FLAG_PARTIALLY_OBSCURED)
        assertTrue("an obscured touch decided: $calls", calls.isEmpty())
        // The refusal is explained, in words.
        rule.onNodeWithTag("consent-overlay").assertIsDisplayed()
        rule.onNodeWithText(OVERLAY_COPY).assertIsDisplayed()
        // The same touch, unobscured, is a tap: the filter is what refused it.
        tapWithFlags("approval-allow", 0)
        assertEquals(listOf("approval:req-w:allow"), calls)
    }

    @Test fun anObscuredTouchCannotTickTheConfirmationOrAnOption() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        tapWithFlags("grant-confirm", android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        show(ApprovalFixtures.question)
        scrollTo("question-option")
        rule.onAllNodesWithTag("question-option")[0].assertIsOff()
        val first = rule.onAllNodesWithTag("question-option")[0].fetchSemanticsNode().boundsInRoot.center
        rule.runOnUiThread {
            val props = arrayOf(android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER })
            val coords = arrayOf(android.view.MotionEvent.PointerCoords().apply { x = first.x; y = first.y; pressure = 1f; size = 1f })
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val e = android.view.MotionEvent.obtain(0L, 10L, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, FLAG_PARTIALLY_OBSCURED)
                checkNotNull(hostView).dispatchTouchEvent(e)
                e.recycle()
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("question-option")[0].assertIsOff()
    }

    @Test fun aDecisionFromADroppedLinkSaysDeliveryIsUnconfirmed() {
        val key = consentKey("s1", "req-w", fpOf(ApprovalFixtures.write, "req-w"))
        show(ApprovalFixtures.write, actions(decided = setOf(key), unconfirmed = setOf(key)))
        scrollTo("approval-allow")
        rule.onNodeWithTag("consent-unconfirmed").assertIsDisplayed()
        rule.onNodeWithText(UNCONFIRMED_COPY).assertIsDisplayed()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }
    // ---- round 3: identity (N1), lost records (L1), pages (L2), Bundle size (L3) -----------------

    /** [base] with req-g re-raised under the SAME id, wider: a second write path, still network. */
    private fun widerGrants(base: ChatFixtures.Folded, turnId: String): ChatFixtures.Folded {
        val tree = foldTree(base.tree, ev("approval_request", turnId, ts = 2) {
            put("requestId", "req-g"); put("toolId", "perm-1"); put("name", "permissions")
            putJsonArray("choices") {
                addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
            }
            putJsonObject("metadata") {
                put("provider", "codex"); put("kind", "permissions")
                putJsonObject("requestedPermissions") {
                    putJsonObject("fileSystem") {
                        putJsonArray("read") { add("/srv/fixtures"); add("/srv/schema.sql") }
                        putJsonArray("write") { add("/w/report"); add("/etc") }
                    }
                    putJsonObject("network") { put("enabled", true) }
                }
            }
        })
        return ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    private fun assertR2FullAndUnconfirmed() {
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsNotEnabled()
        rule.onAllNodesWithTag("grant-write").assertCountEquals(2)
        rule.onAllNodesWithTag("grant-write")[1].assertIsOn()
        rule.onAllNodesWithTag("grant-read")[0].assertIsOn()
        rule.onNodeWithTag("grant-network").assertIsOn()
    }

    @Test fun aWiderReRaiseWhileTheCardIsOffScreenStartsFromScratch() {
        show(grantsAfterHistory)
        narrowTheGrant()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        rule.runOnIdle { kotlinx.coroutines.runBlocking { listState.scrollToItem(0) } }
        rule.waitForIdle()
        rule.onAllNodesWithTag("approval-card").assertCountEquals(0)
        rule.runOnIdle { fixture = widerGrants(grantsAfterHistory, "g1") }
        rule.waitForIdle()
        scrollTo("approval-choice")
        arm()
        assertR2FullAndUnconfirmed()
        assertTrue(calls.isEmpty())
    }

    @Test fun aWiderReRaiseAcrossStateRestorationStartsFromScratch() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        var f by mutableStateOf(ApprovalFixtures.grants)
        val c = actions()
        tester.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(projection = f.projection, tree = f.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c)
            }
        }
        rule.waitForIdle()
        arm()
        narrowTheGrant()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        // The saved state was written for R1; R2 (same id, wider) is what the restored screen shows.
        f = widerGrants(ApprovalFixtures.grants, "t1")
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        arm()
        assertR2FullAndUnconfirmed()
    }

    @Test fun theSameRequestKeepsItsNarrowingAcrossRestorationButNotTheConfirmation() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        val c = actions()
        tester.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(projection = ApprovalFixtures.grants.projection, tree = ApprovalFixtures.grants.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c)
            }
        }
        rule.waitForIdle()
        arm()
        narrowTheGrant()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        arm()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-network").assertIsOff()
        rule.onAllNodesWithTag("grant-read")[0].assertIsOff()
        // Never saved: losing it only narrows (the operator confirms again).
        rule.onNodeWithTag("grant-confirm").assertIsOff()
    }

    @Test fun aLostRecordFallsBackToTheFullRequest() {
        // L1: whatever drops a card's record (the store's bound), the card comes back fully ticked.
        show(ApprovalFixtures.grants)
        narrowTheGrant()
        rule.runOnIdle { store.clear() }
        rule.waitForIdle()
        rule.onNodeWithTag("grant-network").assertIsOn()
        confirmAndAllowSelected()
        assertEquals(
            listOf("approval:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/fixtures", "/srv/schema.sql"), fileSystemWrite = listOf("/w/report"), networkEnabled = true).toJsonObject()),
            calls,
        )
    }

    /** The question fixture with q-1 re-raised under the same id, a third option on page 1. */
    private fun widerQuestion(): ChatFixtures.Folded {
        val tree = foldTree(ApprovalFixtures.question.tree, ev("question_request", "t1", ts = 2) {
            put("requestId", "q-1"); put("toolId", "ask-1")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", ApprovalFixtures.Q_DB); put("header", "Database"); put("multiSelect", false)
                    putJsonArray("options") {
                        addJsonObject { put("label", "Postgres"); put("description", "") }
                        addJsonObject { put("label", "SQLite"); put("description", "") }
                        addJsonObject { put("label", "DynamoDB"); put("description", "") }
                    }
                }
            }
        })
        return ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    @Test fun aQuestionsPicksAndPageSurviveStateRestoration() {
        fixture = ApprovalFixtures.question // the recorder builds the answer from it
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        val c = actions()
        tester.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                ChatTranscript(projection = ApprovalFixtures.question.projection, tree = ApprovalFixtures.question.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c)
            }
        }
        rule.waitForIdle()
        arm()
        scrollTo("question-next")
        rule.onNodeWithText("SQLite").performClick()
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle()
        arm()
        rule.onNodeWithText("production").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        arm()
        scrollTo("question-submit")
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        rule.onAllNodesWithTag("question-option")[1].assertIsOn()
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        assertEquals(listOf("question:q-1:{${ApprovalFixtures.Q_DB}=SQLite, ${ApprovalFixtures.Q_ENV}=production}"), calls)
    }

    @Test fun aReRaisedQuestionStartsOverOnScreenAndAcrossRestoration() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("SQLite").performClick()
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle()
        rule.runOnIdle { fixture = widerQuestion() }
        rule.waitForIdle()
        arm()
        scrollTo("question-submit")
        // Page 1 of the new request (it has one question), nothing picked.
        rule.onAllNodesWithTag("question-option").assertCountEquals(3)
        rule.onAllNodesWithTag("question-option")[1].assertIsOff()
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("question-validation").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test fun page2sKeysActOnTheirFirstTapRightAfterNext() {
        // ta-coik.13: chat-view.tsx 90fbb9f :1108-1120, the web's next page is answerable at once (no
        // per-page wait). A fresh tap on page 2's Skip, in the frame after Next, skips it and sends.
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread { next() }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        rule.onNodeWithTag("question-submit").assertIsEnabled()
        rule.onNodeWithTag("question-skip").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals("page 2 skipped, page 1's answer sent: $calls", 1, calls.size)
    }

    @Test fun aHugeQuestionIsNeverWrittenIntoTheSavedState() {
        // L3: the store saves indices and the operator's own text only.
        val huge = "Pick one: " + "x".repeat(200_000)
        val tree = foldTree(ApprovalFixtures.question.tree, ev("question_request", "t1", ts = 2) {
            put("requestId", "q-big"); put("toolId", "ask-big")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", huge); put("header", "Huge"); put("multiSelect", false)
                    putJsonArray("options") { addJsonObject { put("label", "y".repeat(50_000)); put("description", "") } }
                }
            }
        })
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-card"))
        rule.onAllNodesWithTag("question-option").onFirst().performClick()
        rule.waitForIdle()
        val saved = store.encode()
        assertTrue("saved state is ${saved.length} chars", saved.length < 1_000)
        assertTrue(!saved.contains("xxxxxxxx") && !saved.contains("yyyyyyyy"))
        // And it restores to the same picks.
        val back = CardStateStore.decode(saved)
        assertEquals(store.encode(), back.encode())
    }
    // ---- round 4 -------------------------------------------------------------------------------

    /** A grants request whose read list repeats a path: `["/a","/b","/a"]` (the reducer does not dedupe). */
    private val duplicatePaths: ChatFixtures.Folded by lazy {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-d"); put("toolId", "perm-d"); put("name", "permissions")
                putJsonArray("choices") { addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") } }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("read") { add("/a"); add("/b"); add("/a") } } }
                }
            },
        )
        ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    @Test fun unTickingOneRowOfADuplicatedPathUnTicksThePath() {
        show(duplicatePaths)
        // A subset-only card: no confirmation box (chat-view.tsx :1270, only for "exact").
        scrollTo("grant-read")
        rule.onAllNodesWithTag("grant-read")[2].performClick() // the second "/a" row
        // Both "/a" rows are one permission: both off.
        rule.onAllNodesWithTag("grant-read")[0].assertIsOff()
        rule.onAllNodesWithTag("grant-read")[2].assertIsOff()
        rule.onAllNodesWithTag("grant-read")[1].assertIsOn()
        confirmAndAllowSelected()
        assertEquals(listOf("approval:req-d:some:" + GrantedPermissions(fileSystemRead = listOf("/b")).toJsonObject()), calls)
    }

    /** The web's box is about the complete expansion, so a path box changing leaves it ticked. */
    @Test fun aTickChangeLeavesTheConfirmation() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        rule.onNodeWithTag("grant-network").performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        rule.onNodeWithText("Confirm the complete permission expansion shown above.").assertExists()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsEnabled()
        assertTrue(calls.isEmpty())
    }

    /** chat-view.tsx :1193-1195: "exact" sends the requested expansion as it came, whatever the boxes say. */
    @Test fun allowAllGrantsTheCompleteRequestWhateverIsTicked() {
        show(ApprovalFixtures.grants)
        narrowTheGrant()
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("""approval:req-g:all:{"fileSystem":{"read":["/srv/fixtures","/srv/schema.sql"],"write":["/w/report"]},"network":{"enabled":true}}"""),
            calls,
        )
    }

    @Test fun aRecordEvictedWhileOnScreenFallsBackToTheFullRequest() {
        // L3: the store keeps the newest MAX_RECORDS; others' writes evict this card's record.
        show(ApprovalFixtures.grants)
        narrowTheGrant()
        rule.runOnIdle { repeat(CardStateStore.MAX_RECORDS) { store.setGrant("other-$it", GrantSelection(networkOff = true)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("grant-network").assertIsOn() // back to the full request
        assertTrue(calls.isEmpty())
    }

    /** One question text on pages 1 and 3, the options reordered on page 3 (and one more). */
    private val repeatedQuestion: ChatFixtures.Folded by lazy {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(), ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") }, ev("question_request", "t1", ts = 2) {
            put("requestId", "q-r"); put("toolId", "ask-r")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", "DB?"); put("header", "A"); put("multiSelect", false)
                    putJsonArray("options") { addJsonObject { put("label", "Postgres"); put("description", "") }; addJsonObject { put("label", "SQLite"); put("description", "") } }
                }
                addJsonObject {
                    put("question", "Env?"); put("header", "B"); put("multiSelect", false)
                    putJsonArray("options") { addJsonObject { put("label", "staging"); put("description", "") } }
                }
                addJsonObject {
                    put("question", "DB?"); put("header", "C"); put("multiSelect", false)
                    putJsonArray("options") { addJsonObject { put("label", "DynamoDB"); put("description", "") }; addJsonObject { put("label", "SQLite"); put("description", "") }; addJsonObject { put("label", "Postgres"); put("description", "") } }
                }
            }
        })
        ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    @Test fun aRepeatedQuestionWithReorderedOptionsSendsTheLabelTapped() {
        // L1: page 1 picks "SQLite"; page 3 (same text, options reordered) shows SQLite picked, and
        // the answer says SQLite (the web keys picks by LABEL).
        fixture = repeatedQuestion
        show(repeatedQuestion)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("question-card"))
        rule.onAllNodesWithTag("question-card").assertCountEquals(1)
        val sqlite = androidx.compose.ui.test.hasTestTag("question-option") and hasText("SQLite")
        rule.onNode(sqlite).performClick()
        scrollTo("question-next")
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle(); arm()
        scrollTo("question-skip")
        rule.onNodeWithTag("question-skip").performClick() // skip page 2 ("Env?")
        rule.waitForIdle(); arm()
        rule.onNodeWithTag("question-page").assert(hasText("Question 3 of 3"))
        rule.onNode(sqlite).assertIsOn() // the same label, picked on page 1
        rule.onAllNodesWithTag("question-option")[0].assertIsOff() // DynamoDB, first on this page
        // Now pick Postgres HERE: third on this page, first on page 1; the answer names Postgres.
        val postgres = androidx.compose.ui.test.hasTestTag("question-option") and hasText("Postgres")
        rule.onNode(postgres).performClick()
        rule.onNode(postgres).assertIsOn()
        rule.onNode(sqlite).assertIsOff() // single-select: the pick moved
        scrollTo("question-submit")
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        assertEquals(listOf("question:q-r:{DB?=Postgres}"), calls)
    }

    @Test fun identicalContentInTwoSessionsIsTwoCards() {
        val other = foldTree(ApprovalFixtures.grants.tree.put("tetherSessionId", com.tether.app.protocol.tree.JsStr("s2")))
        assertTrue(pendingApprovals(ApprovalFixtures.grants.tree).single().contentFp != pendingApprovals(other).single().contentFp)
    }
    // ---- round 5: the tap reads the store (F1) -------------------------------------------------

    private fun armed() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).assertIsEnabled()
    }

    @Test fun anUntickAndAGrantTapInTheSameGestureSendTheLiveSet() {
        // The verifier's repro: finger 0 lands on Network access, finger 1 on Allow selected, both
        // lift before any frame. The grant is read from the store at the tap: never the pre-untick set.
        armed()
        val net = rule.onNodeWithTag("grant-network").fetchSemanticsNode().boundsInRoot
        val sel = rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("grant-network").performTouchInput {
            down(0, center)
            down(1, sel.center - net.topLeft)
            up(0)
            up(1)
        }
        rule.waitForIdle()
        rule.onNodeWithTag("grant-network").assertIsOff()
        assertTrue("never the pre-untick set: $calls", calls.none { it.contains("network") })
    }

    @Test fun anUntickAndAGrantClickQueuedInOneFrameSendTheLiveSet() {
        armed()
        val untick = rule.onNodeWithTag("grant-network").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val allow = rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            untick()
            allow()
        }
        rule.waitForIdle()
        assertEquals(
            listOf("approval:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/fixtures", "/srv/schema.sql"), fileSystemWrite = listOf("/w/report")).toJsonObject()),
            calls,
        )
    }

    @Test fun anUntickAConfirmationAndBothGrantKeysInOneFrameSendOneDecision() {
        // One card, one decision: the first key that sends wins, the other sends nothing.
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        val untick = rule.onNodeWithTag("grant-network").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val allow = rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val all = rule.onNodeWithText("ALLOW ALL", ignoreCase = true).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            untick()
            allow()
            all()
        }
        rule.waitForIdle()
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("approval:req-g:some:") && !calls.single().contains("network"))
    }

    @Test fun anUnpickAndNextInOneFrameStayOnThePage() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val unpick = rule.onNodeWithText("Postgres").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            unpick()
            next()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 1 of 2"))
    }

    @Test fun allowSelectedSendsWhatIsTickedNow() {
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        rule.onAllNodesWithTag("grant-read")[0].performClick()
        rule.onNodeWithTag("grant-network").performClick()
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = listOf("/w/report")).toJsonObject()), calls)
    }

    @Test fun aPickAndASubmitInOneFrameSendNothingUndrawn() {
        fixture = ApprovalFixtures.question
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle(); arm()
        scrollTo("question-submit")
        rule.onNodeWithText("staging").performClick()
        // Round 6 (L-A): a pick and the submit in one frame: the answer on the wire would be one the
        // operator has not seen drawn, so nothing is sent.
        val production = rule.onNodeWithText("production").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val submit = rule.onNodeWithTag("question-submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            production()
            submit()
        }
        rule.waitForIdle()
        assertTrue("an undrawn answer went out: $calls", calls.isEmpty())
        // Drawn now: the next Submit sends exactly what is on screen.
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        assertEquals(listOf("question:q-1:{${ApprovalFixtures.Q_DB}=Postgres, ${ApprovalFixtures.Q_ENV}=staging, production}"), calls)
    }

    @Test fun aNextAndAnOptionUnderASecondFingerDoNotAnswerThePageLeft() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().boundsInRoot
        val sqlite = rule.onNodeWithText("SQLite").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("question-next").performTouchInput {
            down(0, center)
            down(1, sqlite.center - next.topLeft)
            up(0) // Next: page 2
            up(1) // an option of page 1, which is no longer shown
        }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        assertEquals(mapOf(0 to listOf(0)), store.question(pendingQuestions(ApprovalFixtures.question.tree).single().contentFp).picks)
    }

    @Test fun aNextAndATypedOtherInOneFrameDoNotWriteThePageLeft() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val field = rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("question-other")))
            .fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            next()
            field(androidx.compose.ui.text.AnnotatedString("typed after Next"))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        assertTrue(store.question(pendingQuestions(ApprovalFixtures.question.tree).single().contentFp).other.isEmpty())
    }

    @Test fun aPickChangeAndNextInOneFrameStayOnThePage() {
        // The page stays answered (SQLite replaces Postgres), but Next acts only on what was drawn.
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.waitForIdle()
        val sqlite = rule.onNodeWithText("SQLite").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            sqlite()
            next()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 1 of 2"))
        rule.onNodeWithText("SQLite").assertIsOn()
    }

    @Test fun twoConfirmTapsInOneFrameLeaveItUnconfirmed() {
        // I-A: the box toggles against the live state, so two taps in one frame are on-then-off.
        show(ApprovalFixtures.grants)
        scrollTo("grant-confirm")
        val confirm = rule.onNodeWithTag("grant-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            confirm()
            confirm()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("grant-confirm").assertIsOff()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsNotEnabled()
    }

    @Test fun anOptionAndASubmitUnderTwoFingersSendNothing() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle(); arm()
        scrollTo("question-submit")
        rule.onNodeWithText("staging").performClick()
        rule.waitForIdle()
        val option = rule.onNodeWithText("production").fetchSemanticsNode().boundsInRoot
        val submit = rule.onNodeWithTag("question-submit").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("production").performTouchInput {
            down(0, center)
            down(1, submit.center - option.topLeft)
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertTrue("an undrawn answer went out: $calls", calls.isEmpty())
    }

    @Test fun twoNextTapsInOneFrameMoveOnePage() {
        show(ApprovalFixtures.question)
        scrollTo("question-next")
        rule.onNodeWithText("Postgres").performClick()
        val next = rule.onNodeWithTag("question-next").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnUiThread {
            next()
            next()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
    }

    /** Paths that try to read as something else. */
    private val trickyPaths: ChatFixtures.Folded by lazy {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-t"); put("toolId", "perm-t"); put("name", "permissions")
                putJsonArray("choices") { addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") } }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    putJsonObject("requestedPermissions") {
                        putJsonObject("fileSystem") {
                            putJsonArray("read") { add("/x\n/etc/shadow"); add("/safe\u202Etxt.exe") }
                            putJsonArray("write") { add("/x; no network access"); add("/" + "d".repeat(300)) }
                        }
                        putJsonObject("network") { put("enabled", true) }
                    }
                }
            },
        )
        ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    @Test fun theCardIdentityUsesTheClientsSessionNotTheTreesClaim() {
        // I-1: the tree says it is session "zz"; the client shows it as "s1". The record is s1's.
        val tree = ApprovalFixtures.grants.tree.put("tetherSessionId", com.tether.app.protocol.tree.JsStr("zz"))
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick()
        val request = com.tether.app.client.ConsentGuard.pendingApproval(tree, "req-g")!!
        val turn = com.tether.app.client.ConsentGuard.activeTurnId(tree)!!
        assertTrue(store.grant(com.tether.app.client.ConsentGuard.cardIdentity("s1", turn, request)).networkOff)
        assertTrue(!store.grant(com.tether.app.client.ConsentGuard.cardIdentity("zz", turn, request)).networkOff)
    }

    @Test fun pathsAreShownEscapedCutAndQuoted() {
        show(trickyPaths)
        scrollTo("grant-read")
        val label = listOf("grant-read", "grant-write").flatMap { tag -> rule.onAllNodesWithTag(tag).fetchSemanticsNodes() }
            .joinToString(" | ") { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } }
            // The break-anywhere joiners between characters are layout, not text.
            .filterNot { it == '\u2060' || it == '\u200B' }
        assertTrue(label, label.contains("“/x\\u000A/etc/shadow”"))
        assertTrue(label, label.contains("“/safe\\u202Etxt.exe”"))
        assertTrue(label, label.contains("“/x; no network access”"))
        assertTrue(label, label.contains("…d"))
        assertTrue("no raw control reaches the screen", label.none { it == '\n' || it == '\u202E' })
    }
    // ---- round 7: a confirmation refers to the words that were on screen -----------------------





    @Test fun contextLinesAreEscapedAndIsolated() {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-c"); put("toolId", "c1"); put("name", "command_execution")
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "command")
                    put("reason", "Harmless\u202E; approve")
                    put("cwd", "/w/\u200Bproj")
                    putJsonObject("network") { put("host", "evil\u2066.example"); put("protocol", "https") }
                }
            },
        )
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        scrollTo("approval-card")
        rule.onNodeWithText(displayText("Harmless\u202E; approve"), substring = true).assertExists()
        // Labelled values are also given line-break points (breakAnywhere), after the escaping.
        rule.onNodeWithText(displayPath("/w/\u200Bproj").breakAnywhere(), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(displayText("https://evil\u2066.example").breakAnywhere(), substring = true, useUnmergedTree = true).assertExists()
    }

    // ---- ta-57l: every requested path is a row and every choice grants, as on the web (no app-only cap) ----

    /** A permissions request (exact / subset / deny) for [read] and [write], optionally with a working directory. */
    private fun permissionCard(read: List<String>, write: List<String>, cwd: String? = null, choices: Boolean = true): ChatFixtures.Folded {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-b"); put("toolId", "perm-b"); put("name", "permissions")
                if (choices) putJsonArray("choices") {
                    addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                    addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
                    addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
                }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    if (cwd != null) put("cwd", cwd)
                    putJsonObject("requestedPermissions") {
                        putJsonObject("fileSystem") {
                            putJsonArray("read") { read.forEach { add(it) } }
                            putJsonArray("write") { write.forEach { add(it) } }
                        }
                    }
                }
            },
        )
        return ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    private val tagChar = "\udb40\udc41" // U+E0041: a FORMAT code point, escaped as \u{E0041}

    /**
     * 64 + 64 paths of the reducer's maximum 4096 code points. A plain path is elided to 160 code
     * points when drawn, so it may be all tag characters (the worst escape cost); a RELATIVE path is
     * drawn whole, so it is ordinary characters (4096 drawn characters a row; see the receipt for the
     * escape-heavy relative card, which no layout can draw in seconds).
     */
    private fun worstPaths(relative: Boolean): Pair<List<String>, List<String>> {
        fun path(side: String, i: Int): String {
            val head = if (relative) "/$side$i/../" else "/$side$i/"
            val filler = if (relative) "a" else tagChar
            return head + filler.repeat((4_096 - head.length) / filler.codePointCount(0, filler.length))
        }
        return (0 until 64).map { path("r", it) } to (0 until 64).map { path("w", it) }
    }

    /** The text of every composed path piece (the break-anywhere joiners are layout, not text). */
    private fun shownRows(): List<String> = listOf("grant-read", "grant-write", "grant-path-piece").flatMap { tag -> rule.onAllNodesWithTag(tag).fetchSemanticsNodes() }
        .map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }.filterNot { it == '\u2060' || it == '\u200B' } }

    /** The lazy list's entries of the shown card: every piece of every path, reads first (the card's own [grantRows]). */
    private fun pieces(): List<String> = grantRows(pendingApprovals(fixture.tree).single().requested!!).let { r -> (r.read + r.write).flatten() }

    /**
     * Entry [index] of the lazy path list is reachable: scroll the list to it and its FULL text (one piece,
     * quotes and escapes whole) is composed. No refusal and no "not shown" line anywhere.
     */
    private fun assertEntryReachable(index: Int, all: List<String> = pieces()) {
        rule.onNodeWithTag("grant-paths").performScrollToIndex(index)
        rule.waitForIdle()
        assertTrue("entry $index not composed with its full text", shownRows().any { it.contains(all[index]) })
        rule.onAllNodesWithTag("grant-hidden").assertCountEquals(0)
        rule.onAllNodesWithTag("grant-refused").assertCountEquals(0)
    }

    /**
     * The METRIC (W4-A, ta-57l): wall-clock milliseconds in Robolectric from handing the card to the
     * transcript until the path list is on screen and its FIRST and LAST entries (the last piece of the
     * 64th write path) have been scrolled to and composed with their full text, plus the confirmation in
     * view (System.nanoTime around show() + scrollTo() + the two entry scrolls), asserted under
     * 5,000 ms. Then the card is used: the confirmation is ticked and "Allow all" sends the request as it
     * came (every raw path).
     */
    private fun assertWorstCardDrawsAndGrants(read: List<String>, write: List<String>, label: String) {
        val started = System.nanoTime()
        show(permissionCard(read, write))
        scrollTo("grant-confirm")
        val all = pieces()
        assertEntryReachable(all.size - 1, all)
        assertEntryReachable(0, all)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("ta-57l: $label (64+64 x 4096 code points, ${all.size} lazy entries, first and last composed) in $ms ms")
        assertTrue("$label took $ms ms (bound 5000)", ms < 5_000)
        rule.onNodeWithTag("grant-confirm").performClick()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Allow all", ignoreCase = true))
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, calls.size)
        assertTrue(calls.single().take(120), calls.single().startsWith("approval:req-b:all:"))
        // The raw paths went out whole, first and last of each list.
        assertTrue(calls.single().contains(read.first()) && calls.single().contains(read.last()))
        assertTrue(calls.single().contains(write.first()) && calls.single().contains(write.last()))
    }

    @Test fun theWorstRelativeCardDrawsQuicklyAndCanGrant() {
        val (read, write) = worstPaths(relative = true)
        assertWorstCardDrawsAndGrants(read, write, "worst relative card")
    }

    @Test fun theWorstPlainCardDrawsQuicklyAndCanGrant() {
        val (read, write) = worstPaths(relative = false)
        assertWorstCardDrawsAndGrants(read, write, "worst plain card")
    }

    @Test fun theEscapeHeavyRelativeCardDrawsQuicklyAndCanGrant() {
        // 4096 tag characters a path: each is a 9-character \u{E0041} escape (~36k drawn characters a row, whole).
        val tag = tagChar
        fun path(side: String, i: Int): String {
            val head = "/$side$i/../"
            return head + tag.repeat(4_096 - head.length)
        }
        assertWorstCardDrawsAndGrants((0 until 64).map { path("r", it) }, (0 until 64).map { path("w", it) }, "escape-heavy relative card")
    }

    @Test fun aLongRelativePathIsShownWholeAndGrantsLikeAnyOther() {
        // Used to be refused past 1024 escaped characters (an app-only rule); the web grants it.
        val path = "../" + "a".repeat(3_000) + "/../etc"
        show(permissionCard(listOf("/srv/a", path), emptyList()))
        scrollTo("grant-confirm")
        for (i in pieces().indices) assertEntryReachable(i)
        assertTrue(pieces().size > 2) // 3,000 characters: several pieces, every one reachable above
        rule.onNodeWithTag("grant-paths").performScrollToIndex(0)
        rule.onAllNodesWithTag("grant-read").onFirst().assertIsEnabled()
        rule.onNodeWithTag("grant-confirm").assertIsEnabled().performClick()
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(calls.single().take(80), calls.single().startsWith("approval:req-b:all:") && calls.single().contains(path))
    }

    @Test fun aLongRelativePathGrantsAsASubsetToo() {
        val path = "../" + "a".repeat(3_000)
        show(permissionCard(listOf("/srv/a", path), listOf("/w/b")))
        scrollTo("grant-confirm")
        rule.onNodeWithTag("grant-paths").performScrollToIndex(0)
        rule.onAllNodesWithTag("grant-read")[0].performClick() // untick /srv/a
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        val call = calls.single()
        assertTrue(call, call.startsWith("approval:req-b:some:") && call.contains(path) && call.contains("/w/b") && !call.contains("/srv/a"))
    }

    @Test fun withoutChoicesALongRelativePathStillApproves() {
        show(permissionCard(listOf("/srv/a", "../" + "a".repeat(3_000)), emptyList(), choices = false))
        scrollTo("approval-allow")
        for (i in pieces().indices) assertEntryReachable(i)
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-b:allow"), calls)
    }

    @Test fun withoutChoicesAFullCardApproves() {
        val (read, write) = worstPaths(relative = false)
        show(permissionCard(read, write, choices = false))
        scrollTo("approval-allow")
        assertEntryReachable(127)
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-b:allow"), calls)
    }

    @Test fun aFullCardOfOrdinaryPathsGrants() {
        val read = (0 until 64).map { "/srv/data/file-$it" }
        val write = (0 until 64).map { "/w/out/report-$it" }
        show(permissionCard(read, write))
        scrollTo("grant-confirm")
        assertEquals(128, pieces().size) // one piece a short path
        for (i in 0 until 128) assertEntryReachable(i)
        rule.onNodeWithTag("grant-confirm").performClick()
        // Studio's roomier card puts the choice keys below the confirmation's fold: bring them in.
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().startsWith("approval:req-b:all:") && calls.single().contains("/w/out/report-63"))
    }

    @Test fun theChoiceWordsAreEscapedAndTheTapSendsTheChoiceId() {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-n"); put("toolId", "n1"); put("name", "Ba\u202Esh")
                putJsonArray("choices") {
                    addJsonObject { put("choiceId", "go"); put("label", "Allow\u202E\u200B now"); put("description", "Runs\u2066 it\u2028"); put("permissionGrant", "subset") }
                    addJsonObject { put("choiceId", "no"); put("label", "Deny") }
                }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("read") { add("/a") } } }
                }
            },
        )
        val view = pendingApprovals(tree).single()
        assertEquals("Ba\u202Esh", view.name) // the wire name picks the input renderer; the card draws it through SafeText (ta-28i)
        assertEquals("Allow\\u202E\\u200B now", view.choices[0].label)
        assertEquals("Runs\\u2066 it\\u2028", view.choices[0].description)
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        scrollTo("approval-choice")
        rule.onNodeWithText("Allow\\u202E\\u200B now", ignoreCase = true).assertExists()
        // The tap still sends the CHOICE ID, whatever its label looks like.
        rule.onNodeWithText("Allow\\u202E\\u200B now", ignoreCase = true).performClick()
        rule.waitForIdle()
        assertTrue(calls.single(), calls.single().startsWith("approval:req-n:go:"))
    }

    @Test fun aLongWorkingDirectoryShowsItsRelativeTail() {
        val cwd = "/w/" + "d".repeat(4_000) + "/../.."
        show(permissionCard(listOf("/srv/a"), emptyList(), cwd = cwd))
        scrollTo("approval-card")
        val shown = displayPath(cwd)
        assertTrue(shown, shown.endsWith("/../..\u201d$PDI$RELATIVE_MARKER"))
        rule.onNodeWithText(shown.breakAnywhere(), substring = true, useUnmergedTree = true).assertExists()
        // The directory is context, not a grant.
        scrollTo("grant-confirm")
        rule.onAllNodesWithTag("grant-refused").assertCountEquals(0)
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
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = session, projection = projections[session.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        return vm
    }

    private fun arm() {
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    /**
     * ta-coik.24: as on the web (chat-view.tsx 90fbb9f :1191-1210, :1016-1021: disabled only once
     * `submitted`), an offline or catching-up copy is answerable; a tap the closed link refused
     * (use-tether.ts :337-344, the client's NotConnected) leaves the card answerable.
     */
    @Test fun offlineAndCatchingUpCopiesAreAnswerableAsOnTheWeb() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.write, live = false)
        client.link.value = ConnectionState.Disconnected
        client.consentResult = com.tether.app.client.ConsentResult.NotConnected
        host(client)
        scrollTo("approval-allow")
        rule.onNodeWithText(ConsentLock.Offline.copy).assertDoesNotExist()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)

        rule.runOnIdle {
            client.consentResult = com.tether.app.client.ConsentResult.Sent
            client.link.value = ConnectionState.Connected
        }
        rule.waitForIdle()
        rule.onNodeWithText("Catching up", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(List(2) { "approval:s1:req-w:allow" }, client.consentCalls)
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
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
        arm()
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("consent-sent").assertIsDisplayed()
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    /**
     * ta-coik.26: the web draws the approval and question cards live on a read-only or handed-off
     * session (chat-view.tsx 90fbb9f :3672-3678, no such check) and sends the answer; the server
     * decides. No lock words, a key that is enabled, and a tap that reaches the client once.
     */
    private fun answerOnLocked(locked: com.tether.app.protocol.model.AgentSession, fixture: ChatFixtures.Folded, key: String): ChatTestClient {
        val client = ChatTestClient()
        client.show(locked, fixture)
        val vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.StudioDark)) {
                val projections by client.projections.collectAsStateWithLifecycle()
                ChatScreen(vm = vm, session = locked, projection = projections["s1"], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
            }
        }
        rule.waitForIdle()
        arm()
        scrollTo(key)
        rule.onNodeWithText(ConsentLock.ReadOnly.copy).assertDoesNotExist()
        rule.onNodeWithText(ConsentLock.HandedOff.copy).assertDoesNotExist()
        rule.onNodeWithTag("consent-lock").assertDoesNotExist()
        rule.onNodeWithTag(key).assertIsEnabled().performClick()
        rule.waitForIdle()
        return client
    }

    @Test fun aReadOnlySessionsApprovalCardIsAnswerableAsOnTheWeb() {
        val client = answerOnLocked(session.copy(readOnly = true), ApprovalFixtures.write, "approval-allow")
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    @Test fun aHandedOffSessionsApprovalCardIsAnswerableAsOnTheWeb() {
        val client = answerOnLocked(session.copy(handedOffTo = "s2"), ApprovalFixtures.write, "approval-allow")
        assertEquals(listOf("approval:s1:req-w:allow"), client.consentCalls)
    }

    // ---- round 3: the card state survives a link blip and a tab switch (B1) -------------------

    /** The link drops (as a background grace-period close does) and comes back to the same server. */
    private fun blip(client: ChatTestClient, whileDown: () -> Unit = {}) {
        rule.runOnIdle {
            client.link.value = ConnectionState.Disconnected
            client.origin.value = null
            client.live.value = emptySet()
        }
        rule.waitForIdle()
        whileDown()
        rule.runOnIdle {
            client.link.value = ConnectionState.Connected
            client.origin.value = TEST_ORIGIN
            client.live.value = setOf("s1")
        }
        rule.waitForIdle()
        arm()
    }

    @Test fun aNarrowedGrantSurvivesATransientReconnect() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.grants)
        host(client)
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick()
        rule.onNodeWithTag("grant-network").assertIsOff()
        blip(client) {
            // While the link is down the card (ta-coik.24: not locked, as on the web) still shows what
            // the operator chose.
            rule.onNodeWithText(ConsentLock.Offline.copy).assertDoesNotExist()
            rule.onNodeWithTag("grant-network").assertIsOff()
        }
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertIsOff()
        rule.onAllNodesWithTag("grant-read")[0].assertIsOn()
    }

    @Test fun aQuestionPageSurvivesATransientReconnect() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.question)
        host(client)
        scrollTo("question-next")
        rule.onNodeWithText("SQLite").performClick()
        rule.onNodeWithTag("question-next").performClick()
        rule.waitForIdle()
        arm()
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        blip(client) { rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2")) }
        scrollTo("question-submit")
        rule.onNodeWithTag("question-page").assert(hasText("Question 2 of 2"))
        rule.onNodeWithText("staging").performClick()
        rule.onNodeWithTag("question-submit").performClick()
        rule.waitForIdle()
        // Page 1's pick survived the blip too.
        assertEquals(listOf("question:s1:q-1:{${ApprovalFixtures.Q_DB}=SQLite, ${ApprovalFixtures.Q_ENV}=staging}"), client.consentCalls)
    }

    @Test fun aBlipThenAFurtherNarrowingSendsWhatTheOperatorUnticked() {
        val client = ChatTestClient()
        client.show(session, ApprovalFixtures.grants)
        host(client)
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick() // network off
        blip(client)
        scrollTo("grant-network")
        rule.onAllNodesWithTag("grant-read")[0].performClick() // /srv/fixtures off
        scrollTo("grant-confirm")
        // ta-coik.5: Allow selected sends on the first tap (no confirmation, as on the web).
        rule.onNodeWithText("ALLOW SELECTED", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("approval:s1:req-g:some:" + GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = listOf("/w/report")).toJsonObject()),
            client.consentCalls,
        )
    }

    @Test fun aNarrowingSurvivesASwitchToARunTabAndBack() {
        val tree = foldTree(
            ApprovalFixtures.grants.tree,
            ev("tool_start", "t1", ts = 1) { put("toolId", "task-9"); put("name", "Agent"); putJsonObject("input") { put("description", "Survey") } },
        )
        val client = ChatTestClient()
        client.show(session, ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        val vm = host(client)
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").performClick()
        rule.runOnIdle { vm.selectRun("s1", "t1::task-9") }
        rule.waitForIdle()
        arm()
        rule.onNodeWithTag("grant-network").assertIsOff() // the run tab shows the same card, same state
        rule.runOnIdle { vm.selectRun("s1", null) }
        rule.waitForIdle()
        arm()
        scrollTo("grant-network")
        rule.onNodeWithTag("grant-network").assertIsOff()
    }
}
