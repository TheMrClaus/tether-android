package com.tether.app.client

import com.tether.app.protocol.BrowserProfileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v171: named browser profiles over the main socket (RealTetherClient on a MockWebServer): the frame, id matching, the 10 s wait, a closed link. */
class BrowserProfilesClientTest {
    private val opened = mutableListOf<ConnectionHarness>()
    private lateinit var h: ConnectionHarness

    @After fun tearDown() = opened.forEach { it.close() }

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        h = ConnectionHarness().also(opened::add)
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, readyFrame())
        client.browserProfileRequestIds = { "rid-1" }
        return client to ws
    }

    private fun reply(id: String, vararg names: String) =
        """{"type":"browser_profiles","requestId":"$id","ok":true,"profiles":[${names.joinToString(",") { """{"id":"id-$it","name":"$it","created":1,"lastUsed":2,"heldBy":null}""" }}]}"""

    @Test fun theWaitIsTenSeconds() {
        assertEquals(10_000L, BROWSER_PROFILES_TIMEOUT_MS)
    }

    @Test fun aCreateSendsItsExactKeysAndResolvesWithTheMatchingReply() = runBlocking {
        val (client, ws) = connected()
        val call = async(Dispatchers.Default) { client.browserProfiles(BrowserProfilesRequest.Create("Work")) }
        val frame = h.expectFrame("browser-profiles")
        assertEquals(setOf("type", "action", "requestId", "name"), frame.keys)
        assertEquals("rid-1", frame["requestId"]?.jsonPrimitive?.content)
        ws.send(reply("rid-1", "Work"))
        assertEquals(BrowserProfilesReply(true, profiles = listOf(BrowserProfileInfo("id-Work", "Work", 1, 2, null))), call.await())
    }

    @Test fun aReplyForAnotherRequestDoesNotResolveTheCall() = runBlocking {
        val (client, ws) = connected()
        val call = async(Dispatchers.Default) { client.browserProfiles(BrowserProfilesRequest.List) }
        h.expectFrame("browser-profiles")
        ws.send(reply("someone-else", "Wrong"))
        h.serverBarrier(ws)
        assertTrue("a reply naming another request must not answer this one", !call.isCompleted)
        ws.send(reply("rid-1", "Right"))
        assertEquals(listOf("Right"), call.await().profiles.map { it.name })
    }

    @Test fun aRefusalKeepsTheServersSentenceAndNoAnswerEndsAsUnavailable() = runBlocking {
        val (client, ws) = connected()
        val refused = async(Dispatchers.Default) { client.browserProfiles(BrowserProfilesRequest.Delete("p1")) }
        h.expectFrame("browser-profiles")
        ws.send("""{"type":"browser_profiles","requestId":"rid-1","ok":false,"code":"PROFILE_IN_USE","error":"Held by Fix login.","profiles":[]}""")
        assertEquals(BrowserProfilesReply(false, "PROFILE_IN_USE", "Held by Fix login."), refused.await())

        client.browserProfilesTimeoutMs = 150
        val silent = async(Dispatchers.Default) { client.browserProfiles(BrowserProfilesRequest.List) }
        h.expectFrame("browser-profiles")
        assertEquals(BrowserProfilesReply.Unavailable, silent.await())
    }

    @Test fun aLinkThatIsNotUpSendsNothingAndSaysSo() = runBlocking {
        h = ConnectionHarness().also(opened::add)
        val client = h.newClient()
        assertEquals(BrowserProfilesReply.NotSent, client.browserProfiles(BrowserProfilesRequest.List))
    }
}
