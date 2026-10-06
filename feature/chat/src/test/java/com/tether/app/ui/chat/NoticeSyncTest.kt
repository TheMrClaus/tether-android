package com.tether.app.ui.chat

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
 * T6.6 r3 x T13.2 (SYNC_DESIGN §4.2), on the whole chat screen (ChatSyncTest's pattern), against
 * Catching up, Saved, Not downloaded and a missing entry. ta-coik.23: as on the web (90fbb9f), a
 * notice's X, the limit card's Schedule / Resume now / Dismiss and the scheduled-resume cancel stay
 * live on every such copy and offline, and the tap reaches the client (which sends on an open socket
 * and otherwise says the link is reconnecting); r2: on read-only and handed-off sessions too (the
 * server answers). The Auto-continue pill and sheet rows still stand on the session controls' lock.
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
        androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) { TetherTheme(choiceFor(TetherSkin.StudioDark)) {
            val projections by client.projections.collectAsStateWithLifecycle()
            ChatScreen(vm = vm, session = shown, projection = projections[shown.id], workspaceRoot = "/w", prefs = prefs, showWorkspaceHeader = false)
        } }
    }
    armChat()
}

private fun ComposeContentTestRule.armChat() {
    mainClock.advanceTimeBy(SETTLE_MS)
    waitForIdle()
}

/** A sheet or dialog that just opened: let it stand still and settle ([SETTLE_MS]). */
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

    /**
     * ta-coik.23: the web's X (notice-dismiss-button.tsx 90fbb9f :12-20) is never disabled and its
     * `send` goes out on any open socket (use-tether.ts :337-344, :1657-1659): on every copy that is
     * not live the X stays live and the tap reaches the client, bound to the server it was drawn for.
     */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheNoticeXLiveAsOnTheWeb() {
        val client = client(session, NoticeFixtures.sessionNotices)
        rule.hostChat(client, session)
        val x = "Dismiss external-advancement notice"
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("notice-dismiss"))
        val expected = mutableListOf<String>()
        for ((name, sync) in notLiveCopies("s1")) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription(x).assertIsEnabled().performClick()
            rule.waitForIdle()
            expected += "s1:ext-1@$TEST_ORIGIN"
            assertEquals("$name: the tap reached the client", expected, client.dismissCalls.toList())
        }
    }

    /** ta-coik.23: offline the X is live too; the client says the link is reconnecting (use-tether.ts :337-341). */
    @Test
    fun offlineTheNoticeXStaysLiveAndTheTapReachesTheClient() {
        val client = client(session, NoticeFixtures.sessionNotices)
        client.link.value = ConnectionState.Disconnected
        client.live.value = emptySet()
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("notice-dismiss"))
        rule.onNodeWithContentDescription("Dismiss external-advancement notice").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s1:ext-1@$TEST_ORIGIN"), client.dismissCalls.toList())
    }

    /** ta-coik.23: chat-view.tsx 90fbb9f :1384-1394, the card's keys wait only on a sent choice. */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheLimitCardLiveAsOnTheWeb() {
        val client = client(session, NoticeFixtures.limit)
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        val keys = listOf("schedule", "resume-now", "dismiss")
        val expected = mutableListOf<String>()
        for ((i, copy) in notLiveCopies("s1").withIndex()) {
            val (name, sync) = copy
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
            val key = keys[i % keys.size]
            rule.onNodeWithTag("rate-limit-$key").assertIsEnabled().performClick()
            rule.waitForIdle()
            expected += limitCall(key)
            assertEquals("$name: $key reached the client", expected, client.controlCalls.toList())
            // The sent choice rests the keys for the web's 4 s (chat-view.tsx :1367); let it pass.
            rule.mainClock.advanceTimeBy(4_100)
            rule.waitForIdle()
        }
    }

    @Test
    fun offlineTheLimitCardStaysLiveAndTheTapReachesTheClient() {
        val client = client(session, NoticeFixtures.limit)
        client.link.value = ConnectionState.Disconnected
        client.live.value = emptySet()
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
        rule.onNodeWithTag("rate-limit-resume-now").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(limitCall("resume-now")), client.controlCalls.toList())
    }

    /** ta-coik.23: chat-view.tsx 90fbb9f :3707-3714, the cancel X is never disabled. */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheScheduledResumeCancelLiveAsOnTheWeb() {
        val client = client(session, NoticeFixtures.scheduled)
        rule.hostChat(client, session)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        val cancel = "Cancel scheduled resume"
        val expected = mutableListOf<String>()
        for ((name, sync) in notLiveCopies("s1") + ("offline" to mapOf("s1" to SessionSync(Freshness.Saved, 1L)))) {
            rule.runOnIdle {
                if (name == "offline") {
                    client.link.value = ConnectionState.Disconnected
                    client.live.value = emptySet()
                }
                client.sync.value = sync
            }
            arm()
            rule.onNodeWithContentDescription(cancel).assertIsEnabled().performClick()
            rule.waitForIdle()
            expected += limitCall("dismiss")
            assertEquals("$name: the tap reached the client", expected, client.controlCalls.toList())
        }
    }

    /**
     * ta-coik.23 r2: on a handed-off or read-only session the web draws the card and its keys live
     * (chat-view.tsx 90fbb9f :3693-3700, :1386-1394; no handoff check in session-manager.mjs
     * :2279-2285): every key reaches the client, offline included; the server answers a read-only
     * session's choice with an `error`, shown.
     */
    @Test
    fun aHandedOffSessionsLimitKeysAreLiveAsOnTheWeb() = everyLimitKeyIsLive(session.copy(handedOffTo = "s9"))

    @Test
    fun aReadOnlySessionsLimitKeysAreLiveAsOnTheWeb() = everyLimitKeyIsLive(session.copy(readOnly = true))

    private fun everyLimitKeyIsLive(shown: AgentSession) {
        val client = client(shown, NoticeFixtures.limit)
        client.link.value = ConnectionState.Disconnected
        client.live.value = emptySet()
        client.sync.value = mapOf("s1" to SessionSync(Freshness.Saved, 1L))
        rule.hostChat(client, shown)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-card"))
        rule.onNodeWithTag("rate-limit-status").assertDoesNotExist()
        val expected = mutableListOf<String>()
        for (key in listOf("schedule", "resume-now", "dismiss")) {
            rule.onNodeWithTag("rate-limit-$key").assertIsEnabled().performClick()
            rule.waitForIdle()
            expected += limitCall(key)
            assertEquals(expected, client.controlCalls.toList())
            rule.mainClock.advanceTimeBy(4_100)
            rule.waitForIdle()
        }
    }

    @Test
    fun aHandedOffSessionCancelsItsScheduledResumeOnACopyThatIsNotLive() = cancelIsLiveOnEveryCopy(session.copy(handedOffTo = "s9"))

    @Test
    fun aReadOnlySessionsScheduledResumeCancelIsLiveAsOnTheWeb() = cancelIsLiveOnEveryCopy(session.copy(readOnly = true))

    private fun cancelIsLiveOnEveryCopy(shown: AgentSession) {
        val client = client(shown, NoticeFixtures.scheduled)
        rule.hostChat(client, shown)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("rate-limit-scheduled"))
        val cancel = "Cancel scheduled resume"
        val expected = mutableListOf<String>()
        for ((name, sync) in notLiveCopies("s1") + ("live" to liveCopy("s1"))) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithContentDescription(cancel).assertIsEnabled().performClick()
            rule.waitForIdle()
            expected += limitCall("dismiss")
            assertEquals("$name: the tap reached the client", expected, client.controlCalls.toList())
        }
    }

    /** ta-coik.24: the sheet's rows send whatever the copy, as the web's toggle (chat-view.tsx 90fbb9f :4495). */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheAutoContinueSheetRowsLive() {
        val controlled = SessionControlFixtures.claude
        val client = client(controlled, ComposerFixtures.idle)
        client.sessionControls.value = mapOf(controlled.id to SessionControlFixtures.claudeControls)
        rule.hostChat(client, controlled)
        rule.onNodeWithTag("session-settings-trigger").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Auto-continue", useUnmergedTree = true).performClick()
        // The sheet slides in; let it stand still.
        openAndArm()
        rule.onNodeWithTag("control-option-true").assertIsEnabled()
        for ((name, sync) in notLiveCopies(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("control-option-true").assertIsEnabled()
            rule.onNodeWithTag("control-option-false").assertIsEnabled()
        }
        // On goes out on the first tap from a copy that is not live (ta-coik.7: the web asks nothing), once.
        rule.onNodeWithTag("control-option-true").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        assertEquals(listOf("${controlled.id}:${SessionControl.AutoContinueOnLimit(true)}"), client.controlCalls)
    }
}

/** The wide row (from 64rem): the Auto-continue pill. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class NoticeSyncTabletTest {
    @get:Rule val rule = createComposeRule()

    private fun arm() = rule.armChat()

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

    /**
     * ta-coik.24: the web's Auto-continue toggle sends whatever the link or the copy (chat-view.tsx
     * 90fbb9f :4495, use-tether.ts :1726-1728 through `send`, :337-344): every tap reaches the client.
     */
    @Test
    fun everyCopyThatIsNotLiveLeavesTheAutoContinuePillLive() {
        // Drawn on: each tap turns it off at once (the fixture is not re-folded, so it stays drawn on).
        val (client, controlled) = host(on = true)
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled()
        var expected = 0
        for ((name, sync) in notLiveCopies(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("control-auto-continue")
                .assertIsEnabled()
                .assert(state("On"))
                .performClick()
            rule.waitForIdle()
            expected++
            assertEquals("$name: the tap reaches the client", expected, client.controlCalls.size)
        }
        rule.runOnIdle { client.link.value = com.tether.app.client.ConnectionState.Disconnected }
        arm()
        rule.onNodeWithTag("control-auto-continue").assertIsEnabled().assert(state("On")).performClick()
        rule.waitForIdle()
        assertEquals(List(expected + 1) { "${controlled.id}:${SessionControl.AutoContinueOnLimit(false)}" }, client.controlCalls)
    }

    @Test
    fun anyCopyGrantsAutoContinueOnTheFirstTap() {
        val (client, controlled) = host(on = false)
        for ((_, sync) in notLiveCopies(controlled.id)) {
            rule.runOnIdle { client.sync.value = sync }
            arm()
            rule.onNodeWithTag("control-auto-continue").assertIsEnabled()
        }
        // ta-coik.7 / ta-coik.24: the grant goes on the tap, once, with no question (the web's toggle),
        // here from a copy that is not live.
        rule.onNodeWithTag("control-auto-continue").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(CONFIRM_TITLE).assertCountEquals(0)
        rule.onAllNodesWithTag("escalation-confirm").assertCountEquals(0)
        assertEquals(listOf("${controlled.id}:${SessionControl.AutoContinueOnLimit(true)}"), client.controlCalls)
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
