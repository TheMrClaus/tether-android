package com.tether.app.protocol.fold

import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ta-ozn2.3: v149 `user_message_accepted.from` reaches the folded user block (engines/events.ts peerFrom at fe4aa8c0). */
class ProtocolV150FoldTest {
    private fun userBlock(from: kotlinx.serialization.json.JsonObject?): JsObj {
        val state = foldTree(
            freshTree(),
            ev("turn_started", "t1", seq = 1, ts = 1),
            ev("user_message_accepted", "t1", seq = 2, ts = 2) {
                put("text", "hi")
                if (from != null) put("from", from)
            },
        )
        return ((state["turnsById"] as JsObj)["t1"] as JsObj).let { (it["blocksById"] as JsObj)["user:t1"] as JsObj }
    }

    @Test fun fromIsKeptBoundedAndAMissingIdDropsIt() {
        val kept = userBlock(buildJsonObject { put("sessionId", "src"); put("name", "Alpha") })
        assertEquals("src", ((kept["from"] as JsObj)["sessionId"] as JsStr).value)
        assertEquals("Alpha", ((kept["from"] as JsObj)["name"] as JsStr).value)
        val long = userBlock(buildJsonObject { put("sessionId", "src"); put("name", "n".repeat(500)) })
        assertEquals(80, (((long["from"] as JsObj)["name"]) as JsStr).value.length)
        assertNull(userBlock(buildJsonObject { put("name", "x") })["from"])
        assertNull(userBlock(buildJsonObject { put("sessionId", ""); put("name", "x") })["from"])
        assertNull(userBlock(null)["from"])
    }
}
