package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-coik.21 r2 (security review): [TetherClient.credentialEpoch] moves when the credential in force
 * becomes a different one, on the SAME server too (a device token replaced by a password sign-in), so
 * state held for one sign-in (the GitHub connection's typed token) can be dropped on a swap.
 */
class CredentialEpochTest {
    private val h = ConnectionHarness()

    @After fun tearDown() {
        h.close()
    }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(20_000) { flow.first(predicate) } }

    @Test fun aSignInWithAnotherCredentialOnTheSameServerMovesTheEpoch() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialDeviceToken = "tthr_device")
        h.client = RealTetherClient(
            settings = h.settings,
            httpClient = OkHttpClient(),
            scope = h.scope,
            clock = { h.now.get() },
            backoff = testBackoff(),
            sweepIntervalMs = 3_600_000,
            scheduler = h.scheduler,
        )
        assertEquals(0L, h.client.credentialEpoch.value)
        h.server.enqueue(MockResponse().setResponseCode(401))
        h.client.start()
        await(h.client.signedOutReason) { it == SignedOutReason.GatewayRefused }
        h.server.takeRequest(20, TimeUnit.SECONDS)
        // The stored device token was put in force.
        val loaded = h.client.credentialEpoch.value
        assertTrue("loaded: $loaded", loaded > 0)
        val origin = serverOrigin(h.client.serverUrl.value)

        // The same server, a password sign-in: another credential.
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129,"pairing":true}"""))
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("set-cookie", "tether_session=s; Path=/; HttpOnly"))
        assertEquals(LoginResult.Success, runBlocking { h.client.login(base, "pw", "Operator") })
        assertEquals(origin, serverOrigin(h.client.serverUrl.value))
        assertTrue("swapped: ${h.client.credentialEpoch.value} after $loaded", h.client.credentialEpoch.value > loaded)
    }

    /**
     * r3: the SAME credential stays the same epoch: a dropped socket and its reconnect, and a repeated
     * start(), move nothing (so a reconnect never wipes the GitHub connection's typed token or ends its
     * device flow).
     */
    @Test fun aReconnectOrARepeatedStartWithTheSameCredentialDoesNotMoveTheEpoch() {
        h.newClient(deviceToken = "tthr_device")
        h.enqueueConnect()
        h.client.start()
        val first = h.nextSocket()
        h.handshake(first)
        val epoch = h.client.credentialEpoch.value
        assertTrue("loaded: $epoch", epoch > 0)

        // A dropped socket, then the scheduled reconnect, to Connected again.
        h.enqueueConnect()
        first.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.scheduler.await(::isReconnectDelay).fire()
        h.handshake(h.nextSocket())
        assertEquals(epoch, h.client.credentialEpoch.value)

        // A repeated start() with the same stored credential.
        h.client.start()
        h.await(h.client.connection) { it == ConnectionState.Connected }
        assertEquals(epoch, h.client.credentialEpoch.value)
    }

    /** r3: a sign-in that puts the IDENTICAL credential in force (same server, same session cookie) is the same credential: no move. */
    @Test fun aSignInThatYieldsTheSameCredentialDoesNotMoveTheEpoch() {
        h.server.start()
        val base = h.server.url("/").toString().trimEnd('/')
        h.settings = InMemorySettings(initialBaseUrl = base, initialCookie = "s")
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
        h.server.takeRequest(20, TimeUnit.SECONDS)
        val loaded = h.client.credentialEpoch.value
        assertTrue("loaded: $loaded", loaded > 0)
        h.server.enqueue(MockResponse().setBody("""{"ok":true,"protocolVersion":137,"nativeProtocolFloor":129,"pairing":true}"""))
        h.server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("set-cookie", "tether_session=s; Path=/; HttpOnly"))
        assertEquals(LoginResult.Success, runBlocking { h.client.login(base, "pw", "Operator") })
        assertEquals(loaded, h.client.credentialEpoch.value)
    }
}
