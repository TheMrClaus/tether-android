package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.ConnectionState
import com.tether.app.client.Freshness
import com.tether.app.client.SessionControl
import com.tether.app.client.SessionSync
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * T6.6 r3 x T13.2 (SYNC_DESIGN §4.2): nothing on a copy that is not Live is actionable. Every T6.6
 * key (a notice's X, the limit card's Schedule / Resume now / Dismiss, the scheduled-resume cancel,
 * the Auto-continue pill and sheet rows, and the Auto-continue confirmation) is locked, says why and
 * sends nothing against Catching up, Saved, Not downloaded and a missing entry, even while the
 * client's live set still holds the session (ChatSyncTest's pattern, on the whole chat screen).
 */

/** Every freshness a copy can have that is not Live, plus "no entry" from a client that reports freshness. */
internal fun notLiveCopies(id: String): List<Pair<String, Map<String, SessionSync>>> =
    Freshness.entries.filter { it != Freshness.Live }.map { it.name to mapOf(id to SessionSync(it, 1L)) } +
        ("missing entry" to emptyMap())

internal fun liveCopy(id: String) = mapOf(id to SessionSync(Freshness.Live, 2L))

private fun ComposeContentTestRule.hostChat(client: ChatTestClient, shown: AgentSession) {
    val vm = TetherViewModel(client)
    val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
    setContent {
        TetherTheme(choiceFor(TetherSkin.StudioDark)) {
            val projections by client.projections.collectAsStateWithLifecycle()
            ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
        }
    }
    armChat()
}

private fun ComposeContentTestRule.armChat() {
    mainClock.advanceTimeBy(CONSENT_ARM_DELAY_MS + 100)
    waitForIdle()
}

/** A sheet or dialog that just opened: let it stand still, then wait out the arming delay. */
private fun ComposeContentTestRule.openAndArmChat() {
    mainClock.advanceTimeBy(NAV_SETTLE_MS)
    waitForIdle()
    armChat()
}

private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

private fun text(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(value)))

private const val CONFIRM_TITLE = "Turn on \u2068Auto-continue\u2069?"

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class NoticeSyncTest {
    @get:Rule val rule = createComposeRule()

    private val session = chatSession("s1", historyId = null)

    private fun client(shown: AgentSession, folded: ChatFixtures.Folded) = ChatTestClient().also {
        it.reports = true
        it.show(shown, folded, live = true)
        it.sync.value = liveCopy(shown.id)
    }

    private fun arm() = rule.armChat()

    private fun openAndArm() = rule.openAndArmChat()

    private fun limitCall(action: String, id: String = "s1") = "$id:${SessionControl.RateLimitResume(NoticeFixtures.RESETS_AT, action)}"

    @Test
    fun everyCopyThatIsNotLiveLocksTheNoticeXEvenInTheLiveSet() {
        val client = client(session, NoticeFixtures.sessionNotices)
        rule.hostChat(client, session)
        val x = "Dismiss external-advancement notice"
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("notice-dismiss"))
        rule.onNodeWithContentDescription(x).assertIsEnabled()
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription(x).assertIsNotEnabled().assert(state(NoticeLock.CatchingUp.copy)).performClick()
            rule.waitForIdle()
            assertTrue("$name: a copy that is not live dismissed ${client.dismissCalls}", client.dismissCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy("s1") }
        arm()
        rule.onNodeWithContentDescription(x).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:ext-1@$TEST_ORIGIN"), client.dismissCalls)
    }

    @Test
    fun offlineTheNoticeXSaysItIsASavedCopy() {
        val client = client(session, NoticeFixtures.sessionNotices)
        client.link.value = ConnectionState.Disconnected
        client.live.value = emptySet()
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("notice-dismiss"))
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsNotEnabled().assert(state(NoticeLock.Offline.copy)).performClick()
        rule.waitForIdle()
        assertTrue(client.dismissCalls.isEmpty())
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheLimitCardEvenInTheLiveSet() {
        val client = client(session, NoticeFixtures.limit)
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled()
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            for (key in listOf("rate-limit-schedule", "rate-limit-resume-now", "rate-limit-dismiss")) {
                rule.onNodeWithTag(key).assertIsNotEnabled().performClick()
            }
            rule.waitForIdle()
            rule.onNodeWithTag("rate-limit-status").assert(text(ConsentLock.CatchingUp.copy.replace("answer", "choose")))
            assertTrue("$name: a copy that is not live chose ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy("s1") }
        arm()
        rule.onNodeWithTag("rate-limit-schedule").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(limitCall("schedule")), client.controlCalls)
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheScheduledResumeCancelEvenInTheLiveSet() {
        val client = client(session, NoticeFixtures.scheduled)
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        val cancel = "Cancel scheduled resume"
        rule.onNodeWithContentDescription(cancel).assertIsEnabled()
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription(cancel).assertIsNotEnabled().assert(state(cancelLockCopy(ConsentLock.CatchingUp))).performClick()
            rule.waitForIdle()
            assertTrue("$name: a copy that is not live cancelled ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy("s1") }
        arm()
        rule.onNodeWithContentDescription(cancel).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(limitCall("dismiss")), client.controlCalls)
    }

    @Test
    fun theHandedOffDismissExceptionStillNeedsALiveCopy() {
        // The r2 exception (a handed-off source may decline its prompt) is not an exception to T13.2.
        val source = session.copy(handedOffTo = "s9")
        val client = client(source, NoticeFixtures.limit)
        rule.hostChat(client, source)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-dismiss").assertIsEnabled()
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            for (key in listOf("rate-limit-schedule", "rate-limit-resume-now", "rate-limit-dismiss")) {
                rule.onNodeWithTag(key).assertIsNotEnabled().performClick()
            }
            rule.waitForIdle()
            rule.onNodeWithTag("rate-limit-status")
                .assert(text("This session was handed off. " + ConsentLock.CatchingUp.copy.replace("answer", "dismiss this prompt")))
            assertTrue("$name: a handed-off copy that is not live declined ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy("s1") }
        arm()
        rule.onNodeWithTag("rate-limit-schedule").assertIsNotEnabled()
        rule.onNodeWithTag("rate-limit-dismiss").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(limitCall("dismiss")), client.controlCalls)
    }

    @Test
    fun theHandedOffCancelExceptionStillNeedsALiveCopy() {
        val source = session.copy(handedOffTo = "s9")
        val client = client(source, NoticeFixtures.scheduled)
        rule.hostChat(client, source)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        val cancel = "Cancel scheduled resume"
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription(cancel).assertIsNotEnabled().assert(state(cancelLockCopy(ConsentLock.CatchingUp))).performClick()
            rule.waitForIdle()
            assertTrue("$name: a handed-off copy that is not live cancelled ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy("s1") }
        arm()
        rule.onNodeWithContentDescription(cancel).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(limitCall("dismiss")), client.controlCalls)
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheAutoContinueSheetRowsAndClosesItsConfirmation() {
        val controlled = SessionControlFixtures.claude
        val client = client(controlled, ComposerFixtures.idle)
        client.sessionControls.value = mapOf(controlled.id to SessionControlFixtures.claudeControls)
        rule.hostChat(client, controlled)
        rule.onNodeWithTag("session-settings-trigger").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Auto-continue", useUnmergedTree = true).performClick()
        // The sheet slides in (its rows move, so they re-arm once it stands still).
        openAndArm()
        rule.onNodeWithTag("control-option-true").assertIsEnabled()
        for ((name, sync) in notLiveCopies(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("control-option-true").assertIsNotEnabled().performClick()
            rule.onNodeWithTag("control-option-false").assertIsNotEnabled().performClick()
            rule.waitForIdle()
            rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
            assertTrue("$name: a copy that is not live changed auto-continue ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        // Live again: On only asks; the copy then stops being live under the question, which closes.
        rule.runOnIdle { client.sync.value = liveCopy(controlled.id) }
        arm()
        rule.onNodeWithTag("control-option-true").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(CONFIRM_TITLE).assertExists()
        rule.runOnIdle { client.sync.value = mapOf(controlled.id to SessionSync(Freshness.Saved, 1L)) }
        arm()
        rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.runOnIdle { client.sync.value = liveCopy(controlled.id) }
        arm()
        rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
        assertTrue("nothing was granted: ${client.controlCalls}", client.controlCalls.isEmpty())
    }
}

/** The wide row (from 64rem): the Auto-continue pill, and its confirmation. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class NoticeSyncTabletTest {
    @get:Rule val rule = createComposeRule()

    private fun arm() = rule.armChat()

    private fun openAndArm() = rule.openAndArmChat()

    private fun host(on: Boolean): Pair<ChatTestClient, AgentSession> {
        val controlled = SessionControlFixtures.claude.copy(autoContinueOnLimit = on)
        val client = ChatTestClient().also {
            it.reports = true
            it.show(controlled, ComposerFixtures.idle, live = true)
            it.sessionControls.value = mapOf(controlled.id to SessionControlFixtures.claudeControls)
            it.sync.value = liveCopy(controlled.id)
        }
        rule.hostChat(client, controlled)
        return client to controlled
    }

    @Test
    fun everyCopyThatIsNotLiveLocksTheAutoContinuePillEvenInTheLiveSet() {
        // Drawn on: turning it off would send at once, so a pill that ignored the lock would send.
        val (client, controlled) = host(on = true)
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled()
        for ((name, sync) in notLiveCopies(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("control-auto-continue")
                .assertIsNotEnabled()
                .assert(state(controlLockCopy(ConsentLock.CatchingUp)!!))
                .performClick()
            rule.waitForIdle()
            assertTrue("$name: a copy that is not live changed auto-continue ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        rule.runOnIdle { client.sync.value = liveCopy(controlled.id) }
        arm()
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${controlled.id}:${SessionControl.AutoContinueOnLimit(false)}"), client.controlCalls)
    }

    @Test
    fun theAutoContinueConfirmationClosesWhenTheCopyStopsBeingLive() {
        val (client, controlled) = host(on = false)
        for ((name, sync) in notLiveCopies(controlled.id)) {
            rule.onNodeWithTag("control-auto-continue").assertIsEnabled().performClick()
            rule.waitForIdle()
            rule.onNodeWithText(CONFIRM_TITLE).assertExists()
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
            rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
            // Live again: the question asked on the stale copy does not come back.
            rule.runOnIdle { client.sync.value = liveCopy(controlled.id) }
            arm()
            rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
            assertTrue("$name: nothing was granted: ${client.controlCalls}", client.controlCalls.isEmpty())
        }
        // Asked and confirmed on a live copy: the grant goes, once.
        rule.onNodeWithTag("control-auto-continue").performClick()
        // The dialog animates in (its key moves, so it re-arms once it stands still).
        openAndArm()
        rule.onNodeWithTag("escalation-confirm").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${controlled.id}:${SessionControl.AutoContinueOnLimit(true, confirmed = true)}"), client.controlCalls)
    }
}

/** The centre of the node tagged [tag], in screen pixels (a dialog's key sits in its own window). */
internal fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.screenCentreOf(tag: String): androidx.compose.ui.geometry.Offset =
    onNodeWithTag(tag).fetchSemanticsNode().let { it.positionOnScreen + androidx.compose.ui.geometry.Offset(it.size.width / 2f, it.size.height / 2f) }

/** One tap at [screen] pixels on the topmost window still open (a sheet, else the screen): where a key used to be. */
internal fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.tapScreenAt(screen: androidx.compose.ui.geometry.Offset) {
    val roots = onAllNodes(androidx.compose.ui.test.isRoot())
    val root = roots[roots.fetchSemanticsNodes().size - 1]
    val origin = root.fetchSemanticsNode().positionOnScreen
    root.performTouchInput { click(screen - origin) }
}

/**
 * T6.6 r4: the confirmation's lock inputs are collected with the lifecycle, so while the app is
 * stopped a drop and a reconnect to the same server go unseen. Stopping the app closes the question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class NoticeLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theAutoContinueConfirmationDoesNotSurviveABackgroundReconnect() {
        val controlled = SessionControlFixtures.claude.copy(autoContinueOnLimit = false)
        val client = ChatTestClient().also {
            it.reports = true
            it.show(controlled, ComposerFixtures.idle, live = true)
            it.sessionControls.value = mapOf(controlled.id to SessionControlFixtures.claudeControls)
            it.sync.value = liveCopy(controlled.id)
        }
        rule.hostChat(client, controlled)
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled().performClick()
        rule.openAndArmChat()
        rule.onNodeWithText(CONFIRM_TITLE).assertExists()
        val confirmAt = rule.screenCentreOf("escalation-confirm")
        rule.onNodeWithTag("escalation-confirm").assertIsEnabled()

        // The screen turns off; the link drops and comes back to the same server while stopped.
        val scenario = rule.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        client.link.value = ConnectionState.Disconnected
        client.live.value = emptySet()
        client.sync.value = mapOf(controlled.id to SessionSync(Freshness.Saved, 1L))
        client.link.value = ConnectionState.Connected
        client.live.value = setOf(controlled.id)
        client.sync.value = liveCopy(controlled.id)
        scenario.moveToState(Lifecycle.State.RESUMED)
        rule.openAndArmChat()

        rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        rule.tapScreenAt(confirmAt)
        rule.armChat()
        assertTrue("nothing was granted on the new link: ${client.controlCalls}", client.controlCalls.isEmpty())

        // The operator asks again, on the link that is live now: the grant goes, once.
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled().performClick()
        rule.openAndArmChat()
        rule.onNodeWithTag("escalation-confirm").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("${controlled.id}:${SessionControl.AutoContinueOnLimit(true, confirmed = true)}"), client.controlCalls)
    }
}
