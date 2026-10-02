package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-2uq (T8.1 slice 3): `providers-snapshot` and `refresh-providers` on the wire (RealTetherClient
 * over a MockWebServer socket). Every ready asks for the catalog (use-tether.ts:800); a push replaces
 * it; the model browser's Retry / Refresh sends `{type:"refresh-providers", providers:[key]}` once per
 * row while it is in flight, ignores repeated taps, refuses a row the current socket's catalog does
 * not list, and is dropped with its socket (never resent on the next one).
 */
class ProviderRefreshTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private fun catalog(codexFetchedAt: Long, codexStatus: String = "error") =
        """{"type":"providers-snapshot","entries":[""" +
            """{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[{"value":"m1","displayName":"M1"}],"fetchedAt":50},""" +
            """{"key":"codex","provider":"codex","status":"$codexStatus","error":"codex app-server timed out","models":[],"fetchedAt":$codexFetchedAt}]}"""

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        return client to ws
    }

    private fun withCatalog(): Pair<RealTetherClient, WebSocket> {
        val (client, ws) = connected()
        ws.send(catalog(100))
        h.await(client.providerCatalogLive) { it }
        return client to ws
    }

    private fun refreshes(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == "refresh-providers" }

    private fun reconnect(client: RealTetherClient, ws: WebSocket): WebSocket {
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        val next = h.nextSocket()
        h.handshake(next)
        return next
    }

    @Test
    fun everyReadyAsksForTheCatalogOnceInOrder() {
        h.separateCatalogRequests = false
        val (client, ws) = connected()
        val asked = h.framesUntilBarrier().filter { it.type() == "providers-snapshot" }
        assertEquals(1, asked.size)
        assertEquals(setOf("type"), asked.single().keys)
        reconnect(client, ws)
        assertEquals("asked again on the new socket", 1, h.framesUntilBarrier().count { it.type() == "providers-snapshot" })
    }

    @Test
    fun aPushReplacesTheCatalogWithItsStamp() {
        val (client, ws) = withCatalog()
        assertEquals(100L, client.providerCatalog.value.first { it.key == "codex" }.fetchedAt)
        ws.send(catalog(200, codexStatus = "ready"))
        h.await(client.providerCatalog) { list -> list.first { it.key == "codex" }.status == "ready" }
        assertEquals(200L, client.providerCatalog.value.first { it.key == "codex" }.fetchedAt)
    }

    /**
     * ta-coik.4: every tap sends, as the web's Refresh (model-browser.tsx:621-627, disabled only while
     * the row is `loading`; use-tether.ts:1863 refreshProviders has no throttle). Negative control: a
     * row this socket's catalog does not list is never named.
     */
    @Test
    fun everyRetryTapSendsLikeTheWeb() {
        val (client, ws) = withCatalog()
        val epoch = client.linkEpoch.value
        repeat(3) { assertEquals("tap $it", ProviderRefreshResult.Sent, client.refreshProviders("codex", epoch)) }
        val sent = refreshes()
        assertEquals("no 2 s debounce, no in-flight hold", 3, sent.size)
        for (frame in sent) {
            assertEquals(setOf("type", "providers"), frame.keys)
            assertEquals(listOf("codex"), (frame["providers"] as JsonArray).map { (it as JsonPrimitive).content })
        }
        // After a push, still every tap.
        ws.send(catalog(300))
        h.await(client.providerCatalog) { list -> list.first { it.key == "codex" }.fetchedAt == 300L }
        assertEquals(ProviderRefreshResult.Sent, client.refreshProviders("codex", epoch))
        assertEquals(ProviderRefreshResult.Sent, client.refreshProviders("codex", epoch))
        assertEquals(2, refreshes().size)
        assertEquals(ProviderRefreshResult.NotOffered, client.refreshProviders("gemini", epoch))
        assertTrue(refreshes().isEmpty())
    }

    @Test
    fun aSocketChangeDropsTheRefreshAndNothingIsResent() {
        val (client, ws) = withCatalog()
        val drawnOn = client.linkEpoch.value
        assertEquals(ProviderRefreshResult.Sent, client.refreshProviders("codex", drawnOn))
        assertEquals(1, refreshes().size)
        val next = reconnect(client, ws)
        assertTrue("nothing resent on the new socket", refreshes().isEmpty())
        // Drawn on the old socket: refused.
        assertEquals(ProviderRefreshResult.NotConnected, client.refreshProviders("codex", drawnOn))
        // The new socket's catalog is not in yet: the old one is never trusted.
        val now = client.linkEpoch.value
        assertEquals(ProviderRefreshResult.NotOffered, client.refreshProviders("codex", now))
        next.send(catalog(100))
        h.await(client.providerCatalogLive) { it }
        assertEquals(ProviderRefreshResult.Sent, client.refreshProviders("codex", now))
        assertEquals(1, refreshes().size)
    }

    @Test
    fun offlineNothingIsSent() {
        val (client, ws) = withCatalog()
        val epoch = client.linkEpoch.value
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertEquals(ProviderRefreshResult.NotConnected, client.refreshProviders("codex", epoch))
    }
}
