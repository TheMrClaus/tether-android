package com.tether.app.client

import com.tether.app.protocol.PROTOCOL_VERSION
import com.tether.app.protocol.ServerMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {

    // ---- Backoff -----------------------------------------------------------

    @Test
    fun backoffDoublesUpToTheCapWithEqualJitter() {
        val low = Backoff(baseMs = 1_000, capMs = 30_000, random = { 0.0 })
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), List(7) { low.next() })
        val high = Backoff(baseMs = 1_000, capMs = 30_000, random = { 1.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { high.next() })
    }

    @Test
    fun backoffNeverDropsBelowHalfTheBaseAndResets() {
        var r = 0.0
        val b = Backoff(baseMs = 1_000, capMs = 30_000, random = { r })
        repeat(50) { r = (it % 10) / 10.0; assertTrue(b.next() in 500L..30_000L) }
        assertEquals(50, b.attempts)
        b.reset()
        r = 0.0
        assertEquals(500L, b.next())
    }

    @Test
    fun backoffSurvivesVeryLongOutagesWithoutOverflow() {
        val b = Backoff(baseMs = 1_000, capMs = 30_000, random = { 1.0 })
        repeat(10_000) { b.next() }
        assertEquals(30_000L, b.next())
    }

    // ---- Compatibility (D5 native window) -----------------------------------

    @Test
    fun windowAcceptsFloorToServerVersionInclusive() {
        assertNull(Compatibility.evaluate(132, 132))
        assertNull(Compatibility.evaluate(140, 100))
        assertNull(Compatibility.evaluate(serverProtocolVersion = null, nativeProtocolFloor = 129))
        // The window is inclusive at both ends, for any client version.
        assertNull(Compatibility.evaluate(129, 129, clientVersion = 129))
    }

    /**
     * ta-koy: the hello-compat window (lib/hello-compat.mjs) for this app at v132: served when
     * `floor <= 132 <= server`. What production advertises (tether 79c3d37: PROTOCOL_VERSION 132,
     * NATIVE_PROTOCOL_FLOOR 129) is inside it, with three versions of floor headroom; a server
     * still at 129..131 now refuses it (server_too_old), and a floor raised past 132 needs an app
     * update (client_too_old).
     */
    @Test
    fun helloCompatWindowFor132() {
        assertEquals(132, PROTOCOL_VERSION)
        assertNull(Compatibility.evaluate(serverProtocolVersion = 132, nativeProtocolFloor = 129))
        for (floor in 129..132) assertNull("floor $floor", Compatibility.evaluate(132, floor))
        for (server in 132..140) assertNull("server $server", Compatibility.evaluate(server, 129))
        for (server in 129..131) {
            assertEquals("server $server", IncompatibleReason.ServerTooOld, Compatibility.evaluate(server, 129)!!.reason)
        }
        assertEquals(IncompatibleReason.ClientTooOld, Compatibility.evaluate(133, 133)!!.reason)
        // The previous app (v129) against the same server is still served: only the app moved.
        assertNull(Compatibility.evaluate(132, 129, clientVersion = 129))
    }

    /**
     * ta-ylh OWNER GATE: the app decodes v133-v135 but still ADVERTISES 132, so every server from
     * the last known deployment (133) through tether main (135, floor 129) serves it. Had it
     * advertised 135, a 133 or 134 server would refuse it (server_too_old) until the owner
     * deploys — which is why raising PROTOCOL_VERSION waits for the owner's deploy.
     */
    @Test
    fun helloCompatWindowAt132CoversServers133To137() {
        assertEquals(132, PROTOCOL_VERSION)
        // T15.8: tether main moved to 137 (floor still 129); a 132 hello is still served.
        for (server in 133..137) assertNull("server $server", Compatibility.evaluate(server, 129))
        for (server in 133..134) {
            assertEquals("server $server", IncompatibleReason.ServerTooOld, Compatibility.evaluate(server, 129, clientVersion = 135)!!.reason)
        }
        assertNull(Compatibility.evaluate(135, 129, clientVersion = 135))
    }

    @Test
    fun windowNamesTheSideThatIsBehind() {
        assertEquals(IncompatibleReason.ClientTooOld, Compatibility.evaluate(134, 133)!!.reason)
        assertEquals(IncompatibleReason.ServerTooOld, Compatibility.evaluate(128, 120, clientVersion = 129)!!.reason)
        // v128 and older servers have no native window at all.
        assertEquals(
            Incompatibility(IncompatibleReason.ServerTooOld, 128, null),
            Compatibility.evaluate(128, null),
        )
    }

    @Test
    fun mismatchFramesMapByReasonThenByVersion() {
        val native = Compatibility.fromMismatch(
            ServerMessage.VersionMismatch(131, "update the app", nativeProtocolFloor = 131, serverProtocolVersion = 131, reason = "client_too_old"),
        )
        assertEquals(Incompatibility(IncompatibleReason.ClientTooOld, 131, 131, "update the app"), native)
        assertEquals(
            IncompatibleReason.ServerTooOld,
            Compatibility.fromMismatch(ServerMessage.VersionMismatch(129, null, reason = "server_too_old")).reason,
        )
        // Web-style frames (no reason): the required version decides.
        assertEquals(IncompatibleReason.ServerTooOld, Compatibility.fromMismatch(ServerMessage.VersionMismatch(128, null)).reason)
        assertEquals(IncompatibleReason.ClientTooOld, Compatibility.fromMismatch(ServerMessage.VersionMismatch(140, null)).reason)
        // A bare frame (requiredVersion -1) still halts; updating the app is the advice.
        val bare = Compatibility.fromMismatch(ServerMessage.VersionMismatch(-1, null))
        assertEquals(IncompatibleReason.ClientTooOld, bare.reason)
        assertNull(bare.serverProtocolVersion)
    }

    // ---- ReleaseCheck (D13) ------------------------------------------------

    @Test
    fun releaseTagComparison() {
        assertTrue(ReleaseCheck.isNewer("v0.7.0", "0.6.0"))
        assertTrue(ReleaseCheck.isNewer("0.6.1", "0.6.0"))
        assertTrue(ReleaseCheck.isNewer("v1.0", "0.9.9"))
        assertTrue(ReleaseCheck.isNewer("v0.6.0.1", "0.6.0"))
        assertFalse(ReleaseCheck.isNewer("v0.6.0", "0.6.0"))
        assertFalse(ReleaseCheck.isNewer("v0.5.9", "0.6.0"))
        assertFalse(ReleaseCheck.isNewer("v0.6.0-rc1", "0.6.0"))
        assertFalse(ReleaseCheck.isNewer("nightly", "0.6.0"))
        assertFalse(ReleaseCheck.isNewer("v0.7.0", "dev"))
    }

    @Test
    fun fetchLatestReadsTagAndOnlyTrustsGithubPages() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag_name":"v0.7.0","html_url":"https://github.com/TheMrClaus/tether-android/releases/tag/v0.7.0","assets":[]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"tag_name":"v0.7.1","html_url":"http://evil.example/x"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rate limited"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        server.start()
        try {
            val http = OkHttpClient()
            val url = server.url("/repos/TheMrClaus/tether-android/releases/latest").toString()
            assertEquals(
                ReleaseCheck.Release("v0.7.0", "https://github.com/TheMrClaus/tether-android/releases/tag/v0.7.0"),
                runBlocking { ReleaseCheck.fetchLatest(http, url) },
            )
            assertEquals(ReleaseCheck.RELEASES_PAGE_URL, runBlocking { ReleaseCheck.fetchLatest(http, url) }!!.pageUrl)
            assertNull(runBlocking { ReleaseCheck.fetchLatest(http, url) })
            assertNull(runBlocking { ReleaseCheck.fetchLatest(http, url) })
            assertEquals("application/vnd.github+json", server.takeRequest().getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun fetchLatestNeverFollowsARedirect() {
        val server = MockWebServer()
        // A redirect to a second path on the same server: if it were followed, the tag would parse.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"tag_name":"v9.9.9"}"""))
        server.start()
        try {
            val url = server.url("/repos/TheMrClaus/tether-android/releases/latest").toString()
            assertNull(runBlocking { ReleaseCheck.fetchLatest(OkHttpClient(), url) })
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
