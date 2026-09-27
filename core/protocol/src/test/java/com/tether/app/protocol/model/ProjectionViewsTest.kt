package com.tether.app.protocol.model

import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1D: the zero-copy views and the legacy adapter's tolerance rules. */
class ProjectionViewsTest {

    private val tree = JsCodec.parse(
        """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":"active","activeTurnId":"t1",
            "turnOrder":["t1"],"queuedMessages":[{"queueId":"q1","text":"later"}],"futureKey":{"x":1},
            "turnsById":{"t1":{"turnId":"t1","status":"running","liveTokens":12,"blocks":["b1","b2"],
              "blocksById":{"b1":{"blockId":"b1","kind":"message","text":"hi","done":false},
                            "b2":{"blockId":"b2","kind":"tool","name":"Bash","input":{"command":"ls"},"elapsedSeconds":1.5}},
              "pendingApprovals":{"r1":{"requestId":"r1","toolId":"b2","name":"Bash"}}}}}""",
    ) as JsObj

    @Test
    fun viewsReadTheTreeInPlace() {
        val session = SessionView(tree)
        assertEquals("s1", session.tetherSessionId)
        assertEquals("active", session.status)
        assertEquals(1, session.turnCount)
        assertNotNull(session.activeTurn)
        val turn = session.activeTurn!!
        assertSame(tree["turnsById"].let { (it as JsObj)["t1"] }, turn.obj)
        assertEquals(12.0, turn.liveTokens!!, 0.0)
        assertEquals(2, turn.blockCount)
        assertEquals("hi", turn.blockAt(0)!!.text)
        val tool = turn.block("b2")!!
        assertEquals("Bash", tool.name)
        assertEquals(1.5, tool.elapsedSeconds!!, 0.0)
        assertEquals(false, tool.aborted)
        assertEquals(JsStr("ls"), (tool.input as JsObj)["command"])
        assertEquals(listOf("r1"), turn.pendingApprovals.map { it.requestId })
        assertEquals("later", session.queuedMessages.single().text)
        assertNull(session.turn("missing"))
        assertNull(turn.block("missing"))
    }

    @Test
    fun adapterReadsTheV40SubsetAndMemoizesByIdentity() {
        val adapter = LegacyProjectionAdapter()
        val typed = adapter.adapt(tree)!!
        assertSame(typed, adapter.adapt(tree))
        assertEquals(listOf("b1", "b2"), typed.turnsById.getValue("t1").blocks)
        assertEquals("Bash", typed.turnsById.getValue("t1").pendingApprovals.getValue("r1").name)

        // A change in one block: the other block and the untouched parts keep their instances.
        val turnsById = tree["turnsById"] as JsObj
        val turn = turnsById["t1"] as JsObj
        val blocksById = turn["blocksById"] as JsObj
        val changed = tree.put(
            "turnsById",
            turnsById.put("t1", turn.put("blocksById", blocksById.put("b1", (blocksById["b1"] as JsObj).put("text", JsStr("hi there"))))),
        )
        val next = adapter.adapt(changed)!!
        val before = typed.turnsById.getValue("t1")
        val after = next.turnsById.getValue("t1")
        assertEquals("hi there", after.blocksById.getValue("b1").text)
        assertSame(before.blocksById.getValue("b2"), after.blocksById.getValue("b2"))
        assertSame(before.blocks, after.blocks)
        assertSame(typed.queuedMessages, next.queuedMessages)
        assertEquals(0, adapter.misfits)
    }

    @Test
    fun adapterIsTolerant() {
        val odd = JsCodec.parse(
            """{"tetherSessionId":"s1","provider":"claude","cwd":"/w","status":7,
                "turnOrder":["t1"],"turnsById":{"t1":{"turnId":"t1","runCount":1.5,"blocks":["b1","b2"],
                  "blocksById":{"b1":{"kind":"message","text":"no id"},"b2":{"blockId":"b2"}}}}}""",
        ) as JsObj
        val adapter = LegacyProjectionAdapter()
        val typed = adapter.adapt(odd)!!
        // A misfit optional key falls back to its default; the rest of the object survives.
        assertEquals(Vocab.SESSION_READY, typed.status)
        val turn = typed.turnsById.getValue("t1")
        assertEquals(0, turn.runCount)
        // A block without its id takes the map key; one missing a required key is left out.
        assertEquals("b1", turn.blocksById.getValue("b1").blockId)
        assertTrue("b2" !in turn.blocksById)
        assertTrue(adapter.misfits > 0)
        // Required session fields missing: no typed projection at all.
        assertNull(LegacyProjectionAdapter.adaptOnce(JsCodec.parse("""{"provider":"claude"}""") as JsObj))
    }
}
