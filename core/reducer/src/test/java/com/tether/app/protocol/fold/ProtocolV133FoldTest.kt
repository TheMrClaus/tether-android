package com.tether.app.protocol.fold

import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.model.operatorQueuedMessages
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.fold
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshState
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ta-ylh: v133 (issue #211) queued-message provenance in the fold. The vendored reducer corpus is
 * still 79c3d37 (v132) and never carries `origin` / `noticeKind` (re-sync is T15.8), so these are
 * hand-written: the expected queue below is what the real engines/events.mjs at protocol 135
 * produced folding the same events (tests/queued-message.test.mjs plus truthiness edges).
 */
class ProtocolV133FoldTest {

    private fun added(queueId: String, text: String, origin: Any? = null, noticeKind: Any? = null) =
        evNullTurn("queued_message_added") {
            put("queueId", queueId)
            put("text", text)
            when (origin) {
                is String -> put("origin", origin)
                is Int -> put("origin", origin)
            }
            if (noticeKind is String) put("noticeKind", noticeKind)
        }

    private val events = arrayOf(
        added("legacy", "old draft"),
        added("user", "new draft", origin = "user"),
        added("run", "automated", origin = "system", noticeKind = "spawn"),
        added("empty", "t", origin = "", noticeKind = ""),
        added("odd", "t", origin = 7, noticeKind = "future"),
        added("run", "dup", origin = "user"),
        evNullTurn("queued_message_updated") {
            put("queueId", "run")
            put("text", "edited")
        },
    )

    @Test
    fun theFoldKeepsOriginAndNoticeKindExactlyLikeTheWeb() {
        val state = foldTree(freshTree(), *events)
        val web = """[{"queueId":"legacy","text":"old draft"},{"queueId":"user","text":"new draft","origin":"user"},
            {"queueId":"run","text":"edited","origin":"system","noticeKind":"spawn"},{"queueId":"empty","text":"t"},
            {"queueId":"odd","text":"t","origin":7,"noticeKind":"future"}]"""
        assertEquals(JsCodec.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(web) as JsonArray), state["queuedMessages"])

        // lib/queued-message.mjs operatorQueuedMessages: absent / "user" are the operator's.
        val views = SessionView(state).queuedMessages
        assertEquals(listOf("legacy", "user", "empty"), views.filter { it.isOperatorMessage }.map { it.queueId })
        assertEquals(listOf("user", "user", "system", "user", null), views.map { it.origin })
        assertEquals(listOf(null, null, "spawn", null, "future"), views.map { it.noticeKind })
    }

    @Test
    fun theTypedProjectionKeepsEveryQueueItem() {
        val typed = fold(freshState(), *events).queuedMessages
        assertEquals(listOf("legacy", "user", "run", "empty", "odd"), typed.map { it.queueId })
        assertEquals(listOf("user", "user", "system", "user", null), typed.map { it.originKind })
        assertEquals(listOf(null, null, "spawn", null, "future"), typed.map { it.noticeKindValue })
        assertEquals("edited", typed[2].text)
        // T15.6: the composer's list, lib/queued-message.mjs operatorQueuedMessages.
        assertEquals(listOf("legacy", "user", "empty"), operatorQueuedMessages(typed).map { it.queueId })
    }

    @Test
    fun aPreV133EventFoldsToThePreV133Shape() {
        val state = foldTree(freshTree(), added("q1", "t"))
        assertEquals(setOf("queueId", "text"), (state["queuedMessages"]!! as com.tether.app.protocol.tree.JsArr).single().let { (it as JsObj).keys })
    }
}
