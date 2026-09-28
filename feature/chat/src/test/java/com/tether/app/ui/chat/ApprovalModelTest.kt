package com.tether.app.ui.chat

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.3: where the cards go (chat-view.tsx:3351-3662), which lock applies, and what a choice sends. */
class ApprovalModelTest {

    private fun items(fixture: ChatFixtures.Folded, open: Boolean = false, showApprovals: Boolean = true) =
        buildChatItems(fixture.projection, fixture.tree, showThinking = false, zone = ChatFixtures.zone, groupOpen = { _, d -> open || d }, showApprovals = showApprovals, consentOrigin = TEST_ORIGIN)

    private fun ChatItem.label(): String = when (this) {
        is ChatItem.Block -> "block:${block.blockId}"
        is ChatItem.ToolGroup -> "group:$firstBlockId"
        is ChatItem.Denial -> "denial:${denial.toolId}${if (turnId == null) ":homeless" else ""}${if (nested) ":nested" else ""}"
        is ChatItem.Answered -> "answered:${answered.requestId}"
        is ChatItem.Approval -> "approval:${approval.requestId}"
        is ChatItem.Question -> "question:${question.requestId}${if (question.requestId.isNotEmpty() && answered) ":answered" else ""}"
        else -> this::class.simpleName!!
    }

    @Test fun thePendingApprovalSitsBelowTheTranscript() {
        val labels = items(ApprovalFixtures.write).map { it.label() }
        assertEquals("approval:req-w", labels.last())
        assertTrue(items(ApprovalFixtures.write).last().startsGroup)
        assertEquals(listOf("block:t1:m0", "group:toolu_w"), labels.filter { it.startsWith("block:t1:m") || it.startsWith("group") })
    }

    @Test fun interactiveApprovalsOffHidesTheApprovalCardOnly() {
        assertTrue(items(ApprovalFixtures.write, showApprovals = false).none { it is ChatItem.Approval })
        assertTrue(items(ApprovalFixtures.question, showApprovals = false).any { it is ChatItem.Question })
    }

    @Test fun onlyTheActiveTurnsRequestsAreShownAndAResolvedOneGoes() {
        val resolved = foldTree(ApprovalFixtures.write.tree, ev("approval_resolved", "t1", ts = 1) { put("requestId", "req-w"); put("decision", "allow") })
        val gone = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(resolved)!!, resolved)
        assertTrue(items(gone).none { it is ChatItem.Approval })
        val expired = foldTree(ApprovalFixtures.write.tree, ev("approval_expired", "t1", ts = 1) { put("requestId", "req-w") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(expired)!!, expired)).none { it is ChatItem.Approval })
        // A turn that is not the active one never shows its leftovers (the web reads activeTurn only).
        val ended = foldTree(ApprovalFixtures.write.tree, ev("turn_end", "t1", ts = 1) { put("outcome", "ok") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(ended)!!, ended)).none { it is ChatItem.Approval })
    }

    @Test fun aPendingQuestionReplacesTheAskUserQuestionCardAndACancelledOneGoes() {
        val labels = items(ApprovalFixtures.question).map { it.label() }
        assertEquals("question:q-1", labels.last())
        assertFalse("the AskUserQuestion tool card is suppressed", labels.any { it.contains("ask-1") })
        val cancelled = foldTree(ApprovalFixtures.question.tree, ev("question_cancelled", "t1", ts = 1) { put("requestId", "q-1") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(cancelled)!!, cancelled)).none { it is ChatItem.Question })
    }

    @Test fun anAnswerOnRecordMarksThePendingQuestionAnswered() {
        val tree = foldTree(
            ApprovalFixtures.question.tree,
            ev("question_answered", "t1", ts = 1) { put("requestId", "q-1"); put("toolId", "ask-1") },
        )
        val folded = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
        assertEquals("question:q-1:answered", items(folded).last().label())
    }

    @Test fun theAnsweredRecordTakesTheToolSlotAndABlocklessOneTrailsItsTurn() {
        val labels = items(ApprovalFixtures.answered).map { it.label() }
        val slot = labels.indexOf("answered:q-1")
        assertEquals("block:t1:m0", labels[slot - 1])
        assertEquals("block:t1:m1", labels[slot + 1])
        assertEquals(slot + 2, labels.indexOf("answered:q-2"))
        assertTrue(labels.none { it.startsWith("question") })
    }

    @Test fun denialsFollowTheirCallInsideTheOpenGroupTrailTheirTurnOrSitBelowEverything() {
        val closed = items(ApprovalFixtures.denials).map { it.label() }
        // A closed group hides its cards and the denials anchored to them (the web's <details>).
        assertFalse(closed.any { it == "denial:toolu_b:nested" || it == "denial:child-read:nested" })
        assertTrue(closed.indexOf("denial:toolu_gone") > closed.indexOf("block:t1:m1"))
        assertEquals("denial:toolu_late:homeless", closed.last())

        val open = items(ApprovalFixtures.denials, open = true).map { it.label() }
        assertEquals(open.indexOf("block:toolu_b") + 1, open.indexOf("denial:toolu_b:nested"))
        // A sub-agent's refused call anchors to its PARENT Agent block.
        assertEquals(open.indexOf("block:task-1") + 1, open.indexOf("denial:child-read:nested"))
        val denial = items(ApprovalFixtures.denials, open = true).filterIsInstance<ChatItem.Denial>()
        val child = denial.first { it.denial.toolId == "child-read" }
        assertEquals(RunRef("t1::task-1", "Check fixtures"), child.run)
        assertEquals(DenialTarget("File", "/srv/fixtures/secret.env"), child.target)
        assertEquals(DenialTarget("Command", "rm -rf build/ && git clean -fdx"), denial.first { it.denial.toolId == "toolu_b" }.target)
        assertNull(denial.first { it.denial.toolId == "toolu_b" }.run)
        assertEquals(denial.map { it.key }.distinct(), denial.map { it.key })
    }

    @Test fun theLockNamesWhyInOrder() {
        val s = AgentSession(id = "s1", provider = "claude", name = "n", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
        assertNull(consentLock(connected = true, live = true, session = s))
        assertEquals(ConsentLock.CatchingUp, consentLock(connected = true, live = false, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, live = true, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, live = false, session = s))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = true, live = true, session = s.copy(readOnly = true)))
        assertEquals(ConsentLock.HandedOff, consentLock(connected = true, live = true, session = s.copy(handedOffTo = "s2")))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = false, live = false, session = s.copy(readOnly = true, handedOffTo = "s2")))
    }

    @Test fun aChoiceSendsOnlyWhatItsGrantAllows() {
        val view = pendingApprovals(ApprovalFixtures.grants.tree, TEST_ORIGIN).single()
        val (all, some, deny) = view.choices
        val requested = checkNotNull(view.requested)
        assertEquals(listOf("/srv/fixtures", "/srv/schema.sql"), requested.read)
        assertEquals(listOf("/w/report"), requested.write)
        assertTrue(requested.network)
        assertNull("exact needs the confirmation", pickFor(view, all, exactConfirmed = false, subset = null))
        assertEquals(ApprovalPick("all", requested.exact), pickFor(view, all, exactConfirmed = true, subset = null))
        assertNull("a subset needs something ticked", pickFor(view, some, exactConfirmed = true, subset = subsetGrant(emptySet(), emptySet(), false)))
        val subset = subsetGrant(setOf("/srv/schema.sql"), emptySet(), true)
        assertEquals(GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = null, networkEnabled = true), subset)
        assertEquals(ApprovalPick("some", subset), pickFor(view, some, exactConfirmed = false, subset = subset))
        assertEquals(ApprovalPick("deny", null), pickFor(view, deny, exactConfirmed = false, subset = subset))
        // I5: a subset that ticks EVERYTHING (in any order) is the full expansion: it needs the confirmation.
        val full = subsetGrant(linkedSetOf("/srv/schema.sql", "/srv/fixtures"), setOf("/w/report"), true)!!
        assertNull(pickFor(view, some, exactConfirmed = false, subset = full))
        assertEquals(ApprovalPick("some", full), pickFor(view, some, exactConfirmed = true, subset = full))
        assertTrue(view.needsConfirm)
    }

    @Test fun approvalViewReadsTheWebsFields() {
        val view = pendingApprovals(ApprovalFixtures.choices.tree, TEST_ORIGIN).single()
        assertEquals("command_execution", view.name)
        assertEquals("The command reaches outside the workspace sandbox.", view.reason)
        assertEquals("/w/pipeline", view.cwd)
        assertEquals("https://registry.example.test", view.network)
        assertEquals(listOf("accept", "acceptForSession", "decline"), view.choices.map { it.choiceId })
        assertEquals("Until the session ends", view.choices[1].description)
        assertNull(view.requested)
    }

    @Test fun questionPagingHelpersFollowTheWeb() {
        val q = pendingQuestions(ApprovalFixtures.question.tree, TEST_ORIGIN).single()
        val (db, env) = q.prompts
        assertEquals(listOf("Postgres"), togglePick(emptyList(), db, "Postgres"))
        assertEquals(listOf("SQLite"), togglePick(listOf("Postgres"), db, "SQLite"))
        assertEquals(emptyList<String>(), togglePick(listOf("Postgres"), db, "Postgres"))
        assertEquals(listOf("staging", "production"), togglePick(listOf("staging"), env, "production"))
        assertFalse(isPromptAnswered(db, emptyMap(), mapOf(Q_DB to "  ")))
        assertTrue(isPromptAnswered(db, emptyMap(), mapOf(Q_DB to "Mongo")))
    }

    @Test fun aNonStringRequestIdStillShowsItsCardUnderTheMapKey() {
        // I4: the reducer keys the map by String(requestId); the card uses that key.
        val tree = foldTree(ApprovalFixtures.write.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", 42); put("toolId", "t-42"); put("name", "Bash"); putJsonObject("input") { put("command", "ls") }
        })
        val views = pendingApprovals(tree, TEST_ORIGIN)
        assertEquals(listOf("req-w", "42"), views.map { it.requestId })
    }

    @Test fun theFingerprintFollowsTheRequestTheTurnAndTheServer() {
        val a = pendingApprovals(ApprovalFixtures.write.tree, TEST_ORIGIN).single().fingerprint
        assertEquals(a, pendingApprovals(ApprovalFixtures.write.tree, TEST_ORIGIN).single().fingerprint)
        assertTrue(a != pendingApprovals(ApprovalFixtures.write.tree, "http://other:1").single().fingerprint)
        val wider = foldTree(ApprovalFixtures.write.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-w"); put("toolId", "toolu_w"); put("name", "Write")
            putJsonObject("input") { put("file_path", "/etc/passwd"); put("content", "x") }
        })
        assertTrue(a != pendingApprovals(wider, TEST_ORIGIN).single().fingerprint)
    }

    private companion object {
        const val Q_DB = ApprovalFixtures.Q_DB
    }
}
