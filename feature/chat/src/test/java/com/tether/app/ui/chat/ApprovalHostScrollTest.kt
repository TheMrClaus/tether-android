package com.tether.app.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-4za3: the approval card's path list grows with the card (the web has no max-height); its rows are items of
 * the HOST list (the transcript, the subagent panel). A card past the reducer's 64-path bound is built directly
 * (the reducer bound is bypassed on purpose): its Write row, confirmation and keys are reached by scrolling the
 * host list only, only a few of its rows are composed, and its local state survives rows leaving composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ApprovalHostScrollTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun actions() = ConsentActions(
        sessionId = "s1",
        origin = TEST_ORIGIN,
        lock = null,
        decided = emptySet(),
        questionUnavailable = null,
        onApproval = { requestId, _, choiceId, decision, granted ->
            calls += "approval:$requestId:${choiceId ?: decision}" + (granted?.let { ":" + it.toJsonObject() } ?: "")
            ConsentResult.Sent
        },
        onAnswer = { _, _, _, _ -> ConsentResult.NotConnected },
        onOpenRun = {},
    )

    /** The request object of a permissions card with exactly [read] and [write] (no reducer bound). */
    private fun requestWith(read: List<String>, write: List<String>, choices: Boolean = true): Pair<JsObj, JsObj> {
        val base = foldTree(freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "req-big"); put("toolId", "perm-big"); put("name", "permissions")
                if (choices) putJsonArray("choices") {
                    addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
                    addJsonObject { put("choiceId", "some"); put("label", "Allow selected"); put("permissionGrant", "subset") }
                    addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
                }
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("read") { add("/x") }; putJsonArray("write") { add("/y") } } }
                }
            },
        )
        fun JsonObject.swap(path: List<String>, value: kotlinx.serialization.json.JsonElement): JsonObject =
            if (path.isEmpty()) error("empty") else JsonObject(toMutableMap().also { m ->
                m[path[0]] = if (path.size == 1) value else (m[path[0]] as JsonObject).swap(path.drop(1), value)
            })
        var json = JsCodec.toJson(base) as JsonObject
        val fs = listOf("turnsById", "t1", "pendingApprovals", "req-big", "metadata", "requestedPermissions", "fileSystem")
        json = json.swap(fs + "read", JsonArray(read.map(::JsonPrimitive))).swap(fs + "write", JsonArray(write.map(::JsonPrimitive)))
        val tree = JsCodec.fromJson(json) as JsObj
        val request = (((tree["turnsById"] as JsObj)["t1"] as JsObj)["pendingApprovals"] as JsObj)["req-big"] as JsObj
        return tree to request
    }

    private val bigRead = (0 until 1_200).map { "/srv/data/dir-$it/file-$it.txt" }
    private val someWrite = listOf("/w/out/report-a", "/w/out/report-b", "/w/out/report-c")

    private fun worst(): Pair<List<String>, List<String>> {
        fun path(side: String, i: Int): String {
            val head = "/$side$i/../"
            return head + "a".repeat(4_096 - head.length)
        }
        return (0 until 64).map { path("r", it) } to (0 until 64).map { path("w", it) }
    }

    private fun composedRows() = listOf("grant-read", "grant-write", "grant-path-piece").sumOf { rule.onAllNodesWithTag(it).fetchSemanticsNodes().size }

    private fun agentRun(): SubagentRun {
        val thread = JsObj.of("order" to JsArr.of(listOf(JsStr("e1"))), "entries" to JsObj.of("e1" to JsObj.of("key" to js("e1"), "kind" to js("tool"), "name" to js("Read"), "input" to JsObj.of("file_path" to js("e1")), "done" to JsBool.TRUE, "output" to js("ok"))))
        val block = JsObj.of("blockId" to js("task-1"), "kind" to js("tool"), "name" to js("Agent"), "input" to JsObj.of("description" to js("Read")), "subagent" to thread, "done" to JsBool.FALSE)
        val turn = JsObj.of("turnId" to js("t1"), "status" to js("running"), "blocks" to JsArr.of(listOf(JsStr("task-1"))), "blocksById" to JsObj.of("task-1" to block))
        val tree = freshTree().with("turnOrder" to JsArr.of(JsStr("t1")), "turnsById" to JsObj.of("t1" to turn), "activeTurnId" to js("t1"))
        return collectSubagentRuns(tree).single()
    }

    private val listState = androidx.compose.foundation.lazy.LazyListState()

    private fun showTranscript(read: List<String>, write: List<String>, choices: Boolean = true) {
        val (tree, _) = requestWith(read, write, choices)
        val folded = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
        val c = actions()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 700.dp) {
                ChatTranscript(projection = folded.projection, tree = folded.tree, showThinking = false, onFetchTurns = { _, _ -> }, zone = ChatFixtures.zone, consent = c, listState = listState)
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun showPanel(read: List<String>, write: List<String>, choices: Boolean = true) {
        val (tree, request) = requestWith(read, write, choices)
        val view = approvalView("req-big", request, "t1", "s1")
        val run = agentRun()
        val c = actions()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 700.dp) {
                CompositionLocalProvider(LocalConsent provides c) {
                    SubagentRunTab(run, showThinking = false, pending = listOf(view), pendingQuestions = emptyList(), answeredIds = emptySet())
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
        check(tree.isNotEmpty())
    }

    private fun scrollHost(hostTag: String, tag: String) {
        rule.onNodeWithTag(hostTag).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    private fun scrollHostTop(hostTag: String) {
        rule.onNodeWithTag(hostTag).performScrollToIndex(0)
        rule.waitForIdle()
    }

    /** The shared acceptance, host by host: Write row and confirmation reachable; confirm kept across scrolling; Allow all sends. */
    private fun assertReachableAndKept(hostTag: String) {
        // Lazy: nowhere near all 1,203 rows are composed (a plain Column would compose them all).
        assertTrue("composed ${composedRows()} rows", composedRows() < 100)
        scrollHost(hostTag, "grant-write")
        rule.onAllNodesWithTag("grant-write")[0].assertIsDisplayed().assertIsEnabled()
        scrollHost(hostTag, "grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsDisplayed().assertIsOff().performClick()
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        // The rows around it scroll out of composition, the confirmation with them; back, it is still ticked.
        scrollHostTop(hostTag)
        rule.onAllNodesWithTag("grant-confirm").assertCountEquals(0)
        scrollHost(hostTag, "approval-choice")
        scrollHost(hostTag, "grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsOn()
        scrollHost(hostTag, "approval-choice")
        rule.onNodeWithText("ALLOW ALL", ignoreCase = true).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(1, calls.size)
        assertTrue(calls.single().take(80), calls.single().startsWith("approval:req-big:all:"))
        assertTrue(calls.single().contains(bigRead.last()) && calls.single().contains(someWrite.last()))
        // The latch outlives the keys leaving composition: back at the keys the decision is still "sent".
        scrollHostTop(hostTag)
        scrollHost(hostTag, "consent-sent")
        rule.onNodeWithTag("consent-sent").assertIsDisplayed()
    }

    @Test fun aThousandPathsReachTheWriteRowTheConfirmAndTheKeysInTheTranscript() {
        showTranscript(bigRead, someWrite)
        assertReachableAndKept("chat-transcript")
    }

    @Test fun aThousandPathsReachTheWriteRowTheConfirmAndTheKeysInTheSubagentPanel() {
        showPanel(bigRead, someWrite)
        assertReachableAndKept("subrun-panel")
    }

    @Test fun aThousandPathsApproveAfterScrollingToTheKeysWithoutChoices() {
        showTranscript(bigRead, someWrite, choices = false)
        scrollHost("chat-transcript", "approval-allow")
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-big:allow"), calls)
        rule.onNodeWithTag("approval-allow").assertIsNotEnabled()
    }

    @Test fun aThousandPathsApproveInThePanelWithoutChoices() {
        showPanel(bigRead, someWrite, choices = false)
        scrollHost("subrun-panel", "approval-allow")
        rule.onNodeWithTag("approval-allow").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("approval:req-big:allow"), calls)
    }

    @Test fun theReducersWorstCardComposesInBothHosts() {
        val (read, write) = worst()
        showTranscript(read, write)
        scrollHost("chat-transcript", "grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsDisplayed()
        assertTrue(composedRows() < 40)
    }

    @Test fun theReducersWorstCardComposesInThePanel() {
        val (read, write) = worst()
        showPanel(read, write)
        scrollHost("subrun-panel", "grant-confirm")
        rule.onNodeWithTag("grant-confirm").assertIsDisplayed()
        assertTrue(composedRows() < 40)
    }

    @Test fun theCardHasNoInnerListOrFadeNode() {
        showTranscript(bigRead, someWrite)
        rule.onAllNodesWithTag("grant-paths").assertCountEquals(0)
        rule.onAllNodesWithTag("grant-hidden").assertCountEquals(0)
    }
}
