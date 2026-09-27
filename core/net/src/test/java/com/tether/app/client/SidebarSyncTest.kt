package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T5.1: the sidebar's frames against the vendored wire corpus (parity-corpus/wire), and the
 * sidebar sync folded by RealTetherClient over a real socket.
 */
class SidebarSyncTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun jsonl(name: String): List<JsonObject> =
        File(wire, name).readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    /** The corpus' validated client example of [type] (every one carries `verdict.ok = true`). */
    private fun example(type: String): JsonObject {
        val line = jsonl("client-examples.jsonl").single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private fun s2c(file: String, type: String): List<JsonObject> =
        jsonl(file).filter { it["dir"]?.jsonPrimitive?.content == "s2c" }.map { it["frame"]!!.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == type }

    // ---- client -> server: re-encoded against the recorded valid examples --------------------

    @Test fun markSeenReEncodesAsTheCorpusExample() {
        assertEquals(example("mark-seen"), SidebarSync.markSeen("session-0001", 1767225600000).toJsonObject())
    }

    @Test fun setSessionOrderReEncodesAsTheCorpusExample() {
        assertEquals(example("set-session-order"), SidebarSync.setSessionOrder("/workspace/project", listOf("session-0001")).toJsonObject())
    }

    @Test fun discoverWithWatchReEncodesAsTheCorpusExampleShape() {
        val recorded = example("discover")
        // The sidebar's discover carries no requestId (the durable workspace intent is T8.2's).
        val expected = JsonObject(recorded - "requestId")
        assertEquals(expected, SidebarSync.discover("/workspace/project", mapOf("session-0001" to 1767225600000), listOf("/workspace/project")).toJsonObject())
    }

    @Test fun pinnedWorkspacesReEncodesAsTheCorpusExample() {
        assertEquals(example("set-server-settings"), SidebarSync.setPinnedWorkspaces(listOf("/workspace/project")).toJsonObject())
    }

    @Test fun serverSettingsRequestAndRowActionsReEncodeAsTheCorpusExamples() {
        assertEquals(example("server-settings"), ClientMessage.ServerSettingsRequest.toJsonObject())
        assertEquals(example("pin"), ClientMessage.Pin("session-0001", true).toJsonObject())
        assertEquals(example("rename"), ClientMessage.Rename("session-0001", "Renamed").toJsonObject())
        assertEquals(example("archive"), ClientMessage.Archive("session-0001").toJsonObject())
        assertEquals(example("kill"), ClientMessage.Kill("session-0001").toJsonObject())
    }

    // ---- server -> client: the recorded frames fold ------------------------------------------

    @Test fun recordedSessionOrderSeenAndHistoriesFramesFold() {
        val sync = SidebarSync()
        val orders = s2c("discovery.jsonl", "session-order")
        assertTrue(orders.size >= 2)
        for (raw in orders) assertTrue(sync.onFrame(ServerMessage.parse(raw)))
        val last = orders.last()
        assertEquals(
            last["order"]!!.jsonArray.map { it.jsonPrimitive.content },
            sync.sessionOrders.value.getValue(last["cwd"]!!.jsonPrimitive.content),
        )

        val seen = s2c("discovery.jsonl", "seen").single()
        sync.onFrame(ServerMessage.parse(seen))
        assertEquals(seen["seenAt"]!!.jsonPrimitive.content.toLong(), sync.remoteSeen.value[seen["historyId"]!!.jsonPrimitive.content])

        val histories = s2c("discovery.jsonl", "histories").first()
        sync.onFrame(ServerMessage.parse(histories))
        val listed = sync.historiesByCwd.value.getValue(histories["cwd"]!!.jsonPrimitive.content)
        assertEquals(histories["sessions"]!!.jsonArray.size, listed.size)
        // v68 lastSeenAt and v122 origin reach the sidebar (the unread fold needs them).
        val first = histories["sessions"]!!.jsonArray.first().jsonObject
        assertEquals(first["lastSeenAt"]?.jsonPrimitive?.content?.toLong(), listed.first().lastSeenAt)
        assertEquals(first["origin"]?.jsonPrimitive?.content, listed.first().origin)
    }

    @Test fun anOlderSeenNeverRewindsANewerOne() {
        val sync = SidebarSync()
        sync.onFrame(ServerMessage.Seen("h", 200))
        sync.onFrame(ServerMessage.Seen("h", 100))
        assertEquals(200L, sync.remoteSeen.value["h"])
    }

    @Test fun clearDropsEverything() {
        val sync = SidebarSync()
        sync.onFrame(ServerMessage.SessionOrder("/w", listOf("a")))
        sync.onFrame(ServerMessage.Seen("h", 1))
        sync.clear()
        assertTrue(sync.sessionOrders.value.isEmpty())
        assertTrue(sync.remoteSeen.value.isEmpty())
        assertNull(sync.serverSettings.value)
    }

    // ---- RealTetherClient over a socket ------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    @Test fun theClientSendsTheExactFramesAndFoldsTheReplies() {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        assertTrue(h.client.markSeen("hist-1", 42))
        assertEquals(SidebarSync.markSeen("hist-1", 42).toJsonObject(), h.expectFrame("mark-seen"))

        assertTrue(h.client.discoverWorkspace("/w", mapOf("hist-1" to 42L), listOf("/w", "/w/app")))
        assertEquals(SidebarSync.discover("/w", mapOf("hist-1" to 42L), listOf("/w", "/w/app")).toJsonObject(), h.expectFrame("discover"))

        assertTrue(h.client.setPinnedWorkspaces(listOf("/w/app")))
        assertEquals(SidebarSync.setPinnedWorkspaces(listOf("/w/app")).toJsonObject(), h.expectFrame("set-server-settings"))

        assertTrue(h.client.requestServerSettings())
        h.expectFrame("server-settings")

        // use-tether.ts:1512 — the order is applied locally as soon as it is sent.
        assertTrue(h.client.setSessionOrder("/w", listOf("live:a", "live:b")))
        assertEquals(SidebarSync.setSessionOrder("/w", listOf("live:a", "live:b")).toJsonObject(), h.expectFrame("set-session-order"))
        assertEquals(listOf("live:a", "live:b"), h.client.sessionOrders.value["/w"])

        // The server's canonical broadcast and the other folds.
        ws.send("""{"type":"session-order","cwd":"/w","order":["live:b"]}""")
        h.await(h.client.sessionOrders) { it["/w"] == listOf("live:b") }
        ws.send("""{"type":"seen","historyId":"hist-2","seenAt":77}""")
        h.await(h.client.remoteSeen) { it["hist-2"] == 77L }
        ws.send("""{"type":"histories","cwd":"/w/app","sessions":[{"historyId":"hist-3","provider":"codex","name":"n","cwd":"/w/app","updatedAt":5,"lastSeenAt":4}]}""")
        h.await(h.client.historiesByCwd) { it["/w/app"]?.single()?.lastSeenAt == 4L }
        ws.send(
            """{"type":"server-settings","settings":{"pinnedWorkspaces":["/w/app"]},"envForced":{},"restartRequired":false,"discovered":[],"detected":{}}""",
        )
        h.await(h.client.serverSettings) { it?.settings?.get("pinnedWorkspaces") != null }
    }

    @Test fun nothingIsSentWithoutAHandshakenSocket() {
        h.newClient(configured = false)
        assertEquals(false, h.client.markSeen("h", 1))
        assertEquals(false, h.client.setSessionOrder("/w", listOf("a")))
        assertTrue("an unsent order is not applied", h.client.sessionOrders.value.isEmpty())
    }
}
