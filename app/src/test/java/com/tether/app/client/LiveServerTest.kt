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
import okhttp3.OkHttpClient
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
}
