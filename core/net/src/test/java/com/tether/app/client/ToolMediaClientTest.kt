package com.tether.app.client

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T6.2 on the real client: [TetherClient.toolMedia] carries the one credential in force to the
 * paired server only; `git-diff-file` goes out as the web sends it and its replies (and the
 * `worktree-diff` that invalidates them) fold like use-tether.ts:909-921.
 */
class ToolMediaClientTest {
    private val h = ConnectionHarness()
    private val other = MockWebServer()
    private val png = "/api/tool-media/${"0f".repeat(32)}.png"

    @After fun tearDown() {
        h.close()
        runCatching { other.shutdown() }
    }

    private fun take(): RecordedRequest = h.server.takeRequest(20, TimeUnit.SECONDS)!!

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

    private fun loadedButIdle(cookie: String? = null, token: String? = null) {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = cookie, initialDeviceToken = token)
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        take()
    }

    private fun media() = MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(8)))

    @Test fun aPairedDeviceSendsItsBearerTokenAndNoCookie() = runBlocking {
        loadedButIdle(token = "tthr_device")
        h.server.enqueue(media())
        assertEquals(ToolMediaResult.Ok(8, "image/png"), h.client.toolMedia.fetch(png, 1024, ByteArrayOutputStream()))
        val req = take()
        assertEquals(png, req.path)
        assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test fun aPasswordSessionSendsItsCookieAndNoBearer() = runBlocking {
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(media())
        h.client.toolMedia.fetch(png, 1024, ByteArrayOutputStream())
        val req = take()
        assertEquals("tether_session=cookie", req.getHeader("Cookie"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherOriginNeverReceivesTheCookie() = runBlocking {
        other.start()
        loadedButIdle(cookie = "cookie")
        h.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url(png)))
        assertEquals(ToolMediaResult.Failed(302), h.client.toolMedia.fetch(png, 1024, ByteArrayOutputStream()))
        assertEquals(0, other.requestCount)
    }

    @Test fun aJournaledUrlOnAnotherOriginIsNeverRequested() = runBlocking {
        other.start()
        loadedButIdle(cookie = "cookie")
        val before = h.server.requestCount
        assertEquals(ToolMediaResult.Refused, h.client.toolMedia.fetch(other.url(png).toString(), 1024, ByteArrayOutputStream()))
        assertEquals(before, h.server.requestCount)
        assertEquals(0, other.requestCount)
    }

    @Test fun afterLogoutNothingIsSent() = runBlocking {
        loadedButIdle(token = "tthr_device")
        h.client.logout()
        val before = h.server.requestCount
        assertEquals(ToolMediaResult.SignedOut, h.client.toolMedia.fetch(png, 1024, ByteArrayOutputStream()))
        assertEquals(before, h.server.requestCount)
    }

    @Test fun gitDiffFileGoesOutAsTheWebSendsIt() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        assertEquals(true, h.client.requestGitFileDiff("s1", "src/a b.ts"))
        val frame = h.expectFrame("git-diff-file")
        assertEquals(setOf("type", "sessionId", "path"), frame.keys)
        assertEquals("s1", frame["sessionId"]!!.jsonPrimitive.content)
        assertEquals("src/a b.ts", frame["path"]!!.jsonPrimitive.content)

        assertEquals(true, h.client.requestWorktreeDiff("s1"))
        val summary = h.expectFrame("worktree-diff")
        assertEquals(setOf("type", "sessionId"), summary.keys)
    }

    @Test fun repliesFoldPerSessionAndPathAndAFreshSummaryDropsTheSessionsHunks() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)

        // L5: only replies to requests this client sent are kept; an unsolicited one is dropped.
        ws.send("""{"type":"git-diff-file","sessionId":"s9","path":"unasked.ts","hunks":"+x","truncated":false,"binary":false}""")
        for ((sid, path) in listOf("s1" to "a.ts", "s1" to "b.png", "s2" to "c.ts")) {
            h.client.requestGitFileDiff(sid, path)
            h.expectFrame("git-diff-file")
        }
        ws.send("""{"type":"git-diff-file","sessionId":"s1","path":"a.ts","hunks":"@@ -1 +1 @@\n-a\n+b","truncated":false,"binary":false}""")
        ws.send("""{"type":"git-diff-file","sessionId":"s1","path":"b.png","hunks":"","truncated":false,"binary":true}""")
        ws.send("""{"type":"git-diff-file","sessionId":"s2","path":"c.ts","hunks":"","truncated":true,"binary":false,"error":"Path is outside the repository."}""")
        val diffs = await(h.client.gitFileDiffs) { it["s1"]?.size == 2 && it["s2"] != null }
        assertEquals("an unsolicited reply never lands", null, diffs["s9"])
        // A second reply for an answered request is unsolicited too.
        ws.send("""{"type":"git-diff-file","sessionId":"s1","path":"a.ts","hunks":"FORGED","truncated":false,"binary":false}""")
        h.serverBarrier(ws)
        assertEquals("@@ -1 +1 @@\n-a\n+b", h.client.gitFileDiffs.value["s1"]!!["a.ts"]!!.hunks)
        assertEquals("@@ -1 +1 @@\n-a\n+b", diffs["s1"]!!["a.ts"]!!.hunks)
        assertEquals(true, diffs["s1"]!!["b.png"]!!.binary)
        assertEquals("Path is outside the repository.", diffs["s2"]!!["c.ts"]!!.error)

        ws.send("""{"type":"worktree-diff","sessionId":"s1","diff":{"branch":"b","baseRef":"origin/main","baseCommit":"c","head":"h","commitsAhead":1,"committed":[],"uncommitted":[]}}""")
        val after = await(h.client.gitFileDiffs) { it["s1"]?.isEmpty() == true }
        assertEquals(1, after["s2"]!!.size)
        assertEquals("origin/main", h.client.worktreeDiffs.value["s1"]!!["baseRef"]!!.jsonPrimitive.content)

        // A summary for a session with no cached hunks adds nothing to the cache.
        ws.send("""{"type":"worktree-diff","sessionId":"s3","diff":null}""")
        await(h.client.worktreeDiffs) { it.containsKey("s3") }
        assertEquals(false, h.client.gitFileDiffs.value.containsKey("s3"))
    }

    @Test fun aHundredThousandDeepFrameIsDroppedAndTheConnectionLives() {
        h.enqueueConnect()
        h.newClient()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        ws.send("""{"type":"git-diff-file","sessionId":"s1","path":"a","hunks":""" + "[".repeat(100_000) + "]".repeat(100_000) + ""","truncated":false,"binary":false}""")
        h.serverBarrier(ws)
        assertEquals(ConnectionState.Connected, h.client.connection.value)
    }

    @Test fun beforeTheHandshakeNothingIsSent() {
        h.newClient(configured = false)
        assertEquals(false, h.client.requestGitFileDiff("s1", "a.ts"))
    }
}
