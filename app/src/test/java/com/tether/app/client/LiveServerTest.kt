package com.tether.app.client

import com.tether.app.protocol.model.Vocab
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live acceptance check against a REAL (isolated, fake-engine) Tether server.
 * Skipped unless /tmp/tether-live-base.txt exists; its first line is the base
 * URL and its second line the password. Proves the client speaks the actual
 * wire protocol (not just MockWebServer frames): login, ready, create,
 * attach/snapshot, send with idempotency ack, streamed events folding into a
 * completed turn.
 */
class LiveServerTest {

    @Test
    fun fullTurnAgainstLiveServer() {
        val conf = File("/tmp/tether-live-base.txt")
        assumeTrue("live server config not present", conf.exists())
        val (baseUrl, password) = conf.readLines().let { it[0].trim() to it[1].trim() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = RealTetherClient(
            settings = InMemorySettings(),
            httpClient = OkHttpClient(),
            scope = scope,
        )
        try {
            runBlocking {
                val login = client.login(baseUrl, password)
                assertEquals(LoginResult.Success, login)

                withTimeout(15_000) {
                    while (client.connection.value != ConnectionState.Connected) delay(50)
                }

                client.createSession(provider = "claude", name = "live-verify")
                val sessionId = withTimeout(15_000) {
                    while (client.sessions.value.isEmpty()) delay(50)
                    client.sessions.value.first().id
                }

                client.attach(sessionId)
                withTimeout(15_000) {
                    while (client.projections.value[sessionId] == null) delay(50)
                }

                client.send(sessionId, "hello from android")
                val projection = withTimeout(30_000) {
                    while (true) {
                        val p = client.projections.value[sessionId]
                        val turn = p?.turnOrder?.lastOrNull()?.let { p.turnsById[it] }
                        if (turn != null && turn.status == Vocab.TURN_DONE) return@withTimeout p
                        delay(100)
                    }
                    @Suppress("UNREACHABLE_CODE") error("unreachable")
                }

                val turn = projection.turnsById.getValue(projection.turnOrder.last())
                assertEquals(Vocab.OUTCOME_OK, turn.outcome)
                // Echo turn: the user message block plus at least one agent block.
                val kinds = turn.blocks.map { turn.blocksById.getValue(it).kind }
                assertTrue("expected a user_message block, got $kinds", Vocab.BLOCK_USER_MESSAGE in kinds)
                assertTrue("expected agent content, got $kinds", kinds.any { it != Vocab.BLOCK_USER_MESSAGE })
                assertTrue(
                    "user text should round-trip",
                    turn.blocksById.values.any { it.text?.contains("hello from android") == true },
                )
                assertEquals(Vocab.SESSION_READY, projection.status)
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * The pairing path, end to end against the real server: mint a code the way
     * the browser does, claim it with the production client, and prove the
     * resulting bearer token drives a real session.
     *
     * This is the only test that exercises the cross-repo contract. The unit
     * tests assert the client sends `Authorization: Bearer …` to MockWebServer,
     * and Tether's own suite asserts the server accepts one — but neither proves
     * the two agree, and in particular neither would catch the server rejecting
     * the WebSocket upgrade because OkHttp's handshake differs from a browser's.
     */
    @Test
    fun pairingAgainstLiveServer() {
        val conf = File("/tmp/tether-live-base.txt")
        assumeTrue("live server config not present", conf.exists())
        val (baseUrl, password) = conf.readLines().let { it[0].trim() to it[1].trim() }

        val http = OkHttpClient()
        // Mint a pairing code exactly as the Settings UI does: password login for
        // the cookie, then POST /api/devices/pair with it.
        val loginResponse = http.newCall(
            okhttp3.Request.Builder()
                .url("$baseUrl/api/auth/login")
                .post(
                    """{"password":"$password"}"""
                        .toRequestBody("application/json".toMediaType()),
                )
                .build(),
        ).execute()
        val cookie = loginResponse.use {
            assertEquals(200, it.code)
            it.headers("set-cookie").first().substringBefore(";")
        }
        val code = http.newCall(
            okhttp3.Request.Builder()
                .url("$baseUrl/api/devices/pair")
                .header("Cookie", cookie)
                .post("""{}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use {
            assertEquals(201, it.code)
            Regex("\"code\":\"([^\"]+)\"").find(it.body.string())!!.groupValues[1]
        }

        val settings = InMemorySettings()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = RealTetherClient(settings = settings, httpClient = http, scope = scope)
        try {
            runBlocking {
                // Typed the way a human would, to prove the server's normalisation
                // is what the client relies on rather than a client-side copy.
                val typed = code.lowercase().chunked(4).joinToString("-")
                val paired = client.pair(baseUrl, typed, "live-pairing-test")
                assertEquals(PairResult.Success, paired)

                val stored = settings.deviceToken.first()
                assertTrue("a tthr_ token should be persisted, got $stored", stored?.startsWith("tthr_") == true)
                assertEquals("the cookie slot must stay empty on the pairing path", null, settings.cookie.first())

                // The bearer token must carry a real WebSocket, not just HTTP.
                // Reaching Connected means the upgrade was accepted AND the
                // ready/snapshot handshake completed — the upgrade being exactly
                // where a browser-shaped Origin check would reject OkHttp.
                //
                // Deliberately no createSession/send here: this test shares a
                // server with fullTurnAgainstLiveServer, and leaving a session
                // behind makes that test latch onto the wrong turn.
                withTimeout(20_000) {
                    while (client.connection.value != ConnectionState.Connected) delay(50)
                }
                assertTrue(
                    "a snapshot should arrive over the bearer socket",
                    withTimeout(10_000) {
                        while (client.providers.value.isEmpty()) delay(50)
                        client.providers.value.isNotEmpty()
                    },
                )

                // A burned code must not pair a second device.
                val replayClient = RealTetherClient(
                    settings = InMemorySettings(),
                    httpClient = http,
                    scope = scope,
                )
                assertTrue(
                    "replaying a claimed code must fail",
                    replayClient.pair(baseUrl, code, "replay") is PairResult.Rejected,
                )
            }
        } finally {
            scope.cancel()
        }
    }
}
