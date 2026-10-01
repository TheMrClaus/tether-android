package com.tether.app.protocol.fold

import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.fold
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshState
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.arr
import com.tether.app.protocol.tree.obj
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T15.8: the fold paths of tether 887c222 the vendored corpus does not reach — v136 (issue #229)
 * `queuedAt` edges (unstamped, negative, zero, duplicate) and issue #222 (an over-bound requested
 * permission set is not grantable; the corpus has no such approval). Every expectation below was
 * produced by the real engines/events.mjs at 887c222 folding the same events.
 */
class ProtocolV136FoldTest {

    private fun added(queueId: String, text: String = "t", deferred: Boolean = true, ts: Long? = null) =
        evNullTurn("queued_message_added", ts = ts) {
            put("queueId", queueId)
            put("text", text)
            if (deferred) put("flushMode", "next-call")
        }

    @Test
    fun aDeferredRowCarriesItsJournalStampOnlyWhenStamped() {
        val state = foldTree(
            freshTree(),
            added("end", deferred = false, ts = 1_790_000_001_000),
            added("next", ts = 1_790_000_002_000),
            added("unstamped"),
            added("neg", ts = -5),
            added("zero", ts = 0),
            added("next", text = "dup", ts = 1_790_000_009_000),
        )
        val web = """[{"queueId":"end","text":"t"},
            {"queueId":"next","text":"t","flushMode":"next-call","queuedAt":1790000002000},
            {"queueId":"unstamped","text":"t","flushMode":"next-call"},{"queueId":"neg","text":"t","flushMode":"next-call"},
            {"queueId":"zero","text":"t","flushMode":"next-call","queuedAt":0}]"""
        assertEquals(JsCodec.fromJson(Json.parseToJsonElement(web) as JsonArray), state["queuedMessages"])
        assertEquals(listOf(null, 1_790_000_002_000.0, null, null, 0.0), SessionView(state).queuedMessages.map { it.queuedAt })
    }

    @Test
    fun theTypedProjectionKeepsQueuedAt() {
        val typed = fold(freshState(), added("end", deferred = false, ts = 1_790_000_001_000), added("next", ts = 1_790_000_002_000))
            .queuedMessages
        assertEquals(listOf(null, 1_790_000_002_000.0), typed.map { it.queuedAt })
    }

    private val choices = buildJsonArray {
        add(buildJsonObject { put("choiceId", "allow"); put("label", "Allow"); put("permissionGrant", "exact") })
        add(buildJsonObject { put("choiceId", "some"); put("label", "Some"); put("permissionGrant", "subset") })
        add(buildJsonObject { put("choiceId", "deny"); put("label", "Deny") })
    }

    private fun strings(values: List<String>) = JsonArray(values.map { JsonPrimitive(it) })

    private fun approvals(vararg requests: Pair<String, JsonObject>): JsObj {
        var seq = 1L
        val events = listOf(ev("turn_started", turnId = "t1", seq = seq, ts = 1_790_000_001_000)) +
            requests.map { (id, fileSystem) ->
                seq += 1
                ev("approval_request", turnId = "t1", seq = seq, ts = 1_790_000_000_000 + seq * 1000) {
                    put("requestId", id)
                    put("toolId", "u$id")
                    put("name", "Bash")
                    put("input", JsonObject(emptyMap()))
                    put("choices", choices)
                    put(
                        "metadata",
                        buildJsonObject {
                            put("provider", "codex")
                            put("kind", "permissions")
                            put("requestedPermissions", buildJsonObject {
                                put("fileSystem", fileSystem)
                                put("network", buildJsonObject { put("enabled", true) })
                            })
                        },
                    )
                }
            }
        return foldTree(freshTree(), *events.toTypedArray())["turnsById"].obj!!["t1"].obj!!["pendingApprovals"].obj!!
    }

    private fun choiceIds(approval: JsObj) = approval["choices"].arr!!.map { (it.obj!!["choiceId"] as JsStr).value }

    @Test
    fun anOverBoundPermissionRequestIsNeverGrantable() {
        // Bounds: 64 paths per list, 4096 CODE POINTS per path (JS Array.from(entry).length).
        val emoji = "/" + "😀".repeat(2048) // 2049 code points, 4097 UTF-16 units: in bounds
        val pending = approvals(
            "emoji" to buildJsonObject { put("read", strings(listOf(emoji))); put("write", strings(emptyList())) },
            "long" to buildJsonObject { put("read", strings(emptyList())); put("write", strings(listOf("/" + "a".repeat(4096)))) },
            "many" to buildJsonObject { put("read", strings((0 until 65).map { "/p$it" })) },
            "edge" to buildJsonObject {
                put("read", strings((0 until 64).map { "/p$it" }))
                put("write", strings(listOf("/" + "a".repeat(4095))))
            },
            "bad" to buildJsonObject { put("read", "not-a-list"); put("write", JsonArray(listOf(JsonPrimitive(7)))) },
        )
        for (id in listOf("long", "many")) {
            val approval = pending[id].obj!!
            assertEquals(id, listOf("deny"), choiceIds(approval))
            assertFalse(id, approval["metadata"].obj!!.containsKey("requestedPermissions"))
            assertEquals(id, "permissions", (approval["metadata"].obj!!["kind"] as JsStr).value)
        }
        for (id in listOf("emoji", "edge", "bad")) {
            val approval = pending[id].obj!!
            assertEquals(id, listOf("allow", "some", "deny"), choiceIds(approval))
            assertTrue(id, approval["metadata"].obj!!.containsKey("requestedPermissions"))
        }
        val edge = pending["edge"].obj!!["metadata"].obj!!["requestedPermissions"].obj!!["fileSystem"].obj!!
        assertEquals(64, edge["read"].arr!!.size)
        assertEquals(1, edge["write"].arr!!.size)
        assertEquals(1, pending["emoji"].obj!!["metadata"].obj!!["requestedPermissions"].obj!!["fileSystem"].obj!!["read"].arr!!.size)
    }
}
