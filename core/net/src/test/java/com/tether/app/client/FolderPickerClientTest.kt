package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.TetherJson
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.2: the folder picker's client half (use-tether.ts 90fbb9f, issue #141): the correlated
 * `browse` (:1471-1489, :905-917: the latest wins, a lost reply turns to an error), `create-folder`
 * (:1514-1516, a refusal shown as the server's error) and the durable workspace-activation intent
 * (:1376-1384, :597-621, :874-882, :726-740, :1273-1317) the picker's "Opening…" state reads.
 */
class FolderPickerClientTest {

    private val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire")

    private fun example(type: String): JsonObject {
        val line = File(wire, "client-examples.jsonl").readLines().filter { it.isNotBlank() }
            .map { TetherJson.parseToJsonElement(it).jsonObject }
            .single { it["type"]!!.jsonPrimitive.content == type }
        assertEquals("true", line["verdict"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        return line["frame"]!!.jsonObject
    }

    private val h = ConnectionHarness()
    private val jobs = mutableListOf<Job>()
    private val local = LinkedBlockingQueue<String>()
    private val server = LinkedBlockingQueue<String>()

    @After fun tearDown() {
        jobs.forEach { it.cancel() }
        h.close()
    }

    private fun connected(): WebSocket {
        val client = h.newClient()
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { local.put(it) } }
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.serverErrors.collect { server.put(it.text) } }
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return ws
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun listing(current: String, requestId: String? = null) =
        """{"type":"directories","listing":{"current":"$current","parent":null,"entries":[]}""" +
            (if (requestId != null) ""","requestId":"$requestId"""" else "") + "}"

    // ---- the frames, against the corpus -------------------------------------------------------

    @Test fun createFolderAndBrowseReEncodeAsTheCorpusExamples() {
        assertEquals(example("create-folder"), ClientMessage.CreateFolder("new-folder", "/workspace/project").toJsonObject())
        assertEquals(example("browse"), ClientMessage.Browse("/workspace/project", "browse-1").toJsonObject())
    }

    // ---- browse ----------------------------------------------------------------------------------

    @Test fun theLatestBrowseWinsAndASupersededReplyIsDropped() {
        val ws = connected()
        h.client.browse("/w/a")
        val first = h.expectFrame("browse")
        assertEquals("/w/a", first.str("cwd"))
        assertEquals(BrowseStatus.Phase.Loading, h.client.browseStatus.value?.phase)
        h.client.browse("/w/b")
        val second = h.expectFrame("browse")
        assertTrue(first.str("requestId") != second.str("requestId"))
        // The reply to the first browse arrives late: never shown.
        ws.send(listing("/w/a", first.str("requestId")))
        h.serverBarrier(ws)
        assertNull(h.client.directories.value)
        assertEquals("/w/b", h.client.browseStatus.value?.cwd)
        ws.send(listing("/w/b", second.str("requestId")))
        h.await(h.client.directories) { it?.current == "/w/b" }
        h.await(h.client.browseStatus) { it == null }
        // An uncorrelated listing (create-folder's reply) still lands.
        ws.send(listing("/w/b/new"))
        h.await(h.client.directories) { it?.current == "/w/b/new" }
    }

    @Test fun aBrowseWithNoReplyTurnsToAnErrorAndANewSocketClearsIt() {
        val ws = connected()
        h.client.browse("/w/a")
        h.expectFrame("browse")
        // BROWSE_TIMEOUT_MS (= UNACKED_CLOSE_MS) passes with no listing.
        val timer = h.scheduler.pending().last { it.delayMs == PendingInput.UNACKED_CLOSE_MS }
        timer.fire()
        assertEquals("/w/a" to BrowseStatus.Phase.Error, h.client.browseStatus.value?.let { it.cwd to it.phase })
        // :738-741: a fresh socket clears the stale error.
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())
        assertNull(h.client.browseStatus.value)
    }

    @Test fun aBrowseWithTheLinkDownIsAnErrorAtOnce() {
        h.newClient(configured = false)
        h.client.browse("/w/a")
        assertEquals(BrowseStatus.Phase.Error, h.client.browseStatus.value?.phase)
    }

    // ---- create-folder ---------------------------------------------------------------------------

    @Test fun createFolderSendsTheFrameAndTheServersRefusalIsShown() {
        val ws = connected()
        assertTrue(h.client.createFolder("/w", "reports"))
        assertEquals(ClientMessage.CreateFolder("reports", "/w").toJsonObject(), h.expectFrame("create-folder"))
        // Success: the server answers with the parent's fresh listing.
        ws.send("""{"type":"directories","listing":{"current":"/w","parent":"/","entries":[{"name":"reports","path":"/w/reports"}]}}""")
        h.await(h.client.directories) { l -> l?.entries?.any { it.path == "/w/reports" } == true }
        // Refusal: the server's error, shown as every server error is.
        assertTrue(h.client.createFolder("/w", "reports"))
        h.expectFrame("create-folder")
        ws.send("""{"type":"error","message":"A folder named reports already exists."}""")
        assertEquals("A folder named reports already exists.", server.poll(20, TimeUnit.SECONDS))
    }

    @Test fun createFolderWithTheLinkDownSaysSo() {
        val client = h.newClient(configured = false)
        jobs += h.scope.launch(start = CoroutineStart.UNDISPATCHED) { client.errors.collect { local.put(it) } }
        assertFalse(client.createFolder("/w", "reports"))
        assertEquals("The secure link is reconnecting. Your input was not sent.", local.poll(20, TimeUnit.SECONDS))
    }

    // ---- the workspace intent ----------------------------------------------------------------

    @Test fun aWorkspaceIsOpeningUntilAHistoriesEchoesItsRequestId() {
        val ws = connected()
        assertTrue(h.client.activateWorkspace("/w/new", mapOf("h1" to 5L), listOf("/w/new", "/w/old")))
        assertEquals(ClientMessage.Browse("/w/new").toJsonObject(), h.expectFrame("browse"))
        val discover = h.expectFrame("discover")
        assertEquals("/w/new", discover.str("cwd"))
        assertEquals(listOf("/w/new", "/w/old"), discover["watch"]!!.jsonArray.map { it.jsonPrimitive.content })
        val requestId = discover.str("requestId")
        val opening = h.client.workspaceSelect.value
        assertEquals(WorkspaceSelectStatus("/w/new", requestId, WorkspaceSelectStatus.Phase.Opening), opening)
        // A histories for the same folder WITHOUT the token never confirms it.
        ws.send("""{"type":"histories","cwd":"/w/new","sessions":[]}""")
        h.serverBarrier(ws)
        assertNotNull(h.client.workspaceSelect.value)
        ws.send("""{"type":"histories","cwd":"/w/new","sessions":[],"requestId":"$requestId"}""")
        h.await(h.client.workspaceSelect) { it == null }
    }

    @Test fun anUnconfirmedWorkspaceIsStalledWhileTheLinkIsDownAndRedeliveredOnTheNextReady() {
        val ws = connected()
        h.client.activateWorkspace("/w/new", emptyMap(), listOf("/w/new"))
        h.expectFrame("browse")
        val requestId = h.expectFrame("discover").str("requestId")
        // :620 probeLink: a send on a half-open socket succeeds silently, so the link is probed.
        h.expectFrame("ping")
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        // describeIntent: no socket -> stalled ("couldn't open — retrying").
        h.await(h.client.workspaceSelect) { it?.phase == WorkspaceSelectStatus.Phase.Stalled }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        // :826-831: the ready redelivers it, with the SAME requestId.
        var frame = h.frame()
        while (frame.str("type") != "browse") frame = h.frame()
        assertEquals("/w/new", frame.str("cwd"))
        val again = h.expectFrame("discover")
        assertEquals(requestId, again.str("requestId"))
        h.await(h.client.workspaceSelect) { it?.phase == WorkspaceSelectStatus.Phase.Opening }
        next.send("""{"type":"histories","cwd":"/w/new","sessions":[],"requestId":"$requestId"}""")
        h.await(h.client.workspaceSelect) { it == null }
    }
}
