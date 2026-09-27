package com.tether.app.client

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android 17 local-network block, seen through RealTetherClient. No real
 * network: a fake Dns names the servers, and every TCP connect fails the way
 * the docs say a blocked one does ("TCP Connections will typically result in a
 * timeout error"). The socket factory counts connect attempts, which is how
 * "never touched the network" and "no reconnect loop" are asserted.
 */
class RealTetherClientLocalNetworkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: RealTetherClient? = null

    @After
    fun tearDown() {
        client?.stop()
        scope.cancel()
    }

    /** Toggle to simulate the grant: true = API 37+, targetSdk 37, not granted. */
    private class FakeAccess(@Volatile var restricted: Boolean) : LocalNetworkAccess {
        override fun isRestricted() = restricted
    }

    private class TimingOutSockets : SocketFactory() {
        val connects = AtomicInteger()
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                connects.incrementAndGet()
                throw SocketTimeoutException("connect timed out")
            }
        }
        override fun createSocket(host: String?, port: Int): Socket = throw IOException("unused")
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw IOException("unused")
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw IOException("unused")
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw IOException("unused")
    }

    private val dns = Dns { host ->
        fun at(vararg b: Int) = listOf(InetAddress.getByAddress(host, ByteArray(4) { b[it].toByte() }))
        when (host) {
            // Split-horizon DNS: a public-looking name for a LAN server.
            "tether.home.example" -> at(192, 168, 1, 20)
            "tether.example.com" -> at(203, 0, 113, 5)
            else -> throw UnknownHostException(host)
        }
    }

    private val sockets = TimingOutSockets()
    private val http = OkHttpClient.Builder().dns(dns).socketFactory(sockets).retryOnConnectionFailure(false).build()

    private fun newClient(access: LocalNetworkAccess, settings: SettingsStore = InMemorySettings()) =
        RealTetherClient(
            settings = settings,
            httpClient = http,
            scope = scope,
            backoff = Backoff(baseMs = 50, capMs = 100),
            localNetworkAccess = access,
        ).also { client = it }

    private fun <T> await(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(10_000) { flow.first(predicate) } }

    @Test
    fun `LAN literal while restricted - login and pair report the block without touching the network`() {
        val c = newClient(FakeAccess(restricted = true))
        assertEquals(LoginResult.LocalNetworkBlocked, runBlocking { c.login("http://192.168.1.20:8080", "pw") })
        assertEquals(LoginResult.LocalNetworkBlocked, runBlocking { c.login("nas.local:8080", "pw") })
        assertEquals(PairResult.LocalNetworkBlocked, runBlocking { c.pair("http://[fd00::20]:8080", "ABCD2345", "Pixel") })
        assertEquals(0, sockets.connects.get())
    }

    @Test
    fun `name resolving to a LAN address - a timeout while restricted is the block`() {
        val c = newClient(FakeAccess(restricted = true))
        assertEquals(LoginResult.LocalNetworkBlocked, runBlocking { c.login("http://tether.home.example", "pw") })
        assertEquals(PairResult.LocalNetworkBlocked, runBlocking { c.pair("http://tether.home.example", "ABCD2345", "Pixel") })
    }

    @Test
    fun `remote server - never the permission, even while restricted`() {
        val c = newClient(FakeAccess(restricted = true))
        val result = runBlocking { c.login("http://tether.example.com", "pw") }
        assertTrue("got $result", result is LoginResult.Unreachable)
    }

    @Test
    fun `unrestricted (API below 37, or granted) - a LAN timeout stays an ordinary outage`() {
        val c = newClient(FakeAccess(restricted = false))
        val result = runBlocking { c.login("http://192.168.1.20:8080", "pw") }
        assertTrue("got $result", result is LoginResult.Unreachable)
        assertTrue(sockets.connects.get() > 0)
    }

    @Test
    fun `connect loop - blocked LAN server holds a distinct state with no reconnect loop, and resumes on grant`() {
        val access = FakeAccess(restricted = true)
        val c = newClient(access, InMemorySettings(initialBaseUrl = "http://192.168.1.20:8080", initialCookie = "cookie"))
        c.start()
        await(c.connection) { it == ConnectionState.LocalNetworkBlocked }

        // Many reconnect delays later: still blocked, still zero connect attempts.
        Thread.sleep(500)
        assertEquals(ConnectionState.LocalNetworkBlocked, c.connection.value)
        assertEquals(0, sockets.connects.get())
        // Lifecycle/network nudges re-evaluate and stay blocked, without touching the network.
        c.reconnectIfIdle()
        assertEquals(ConnectionState.LocalNetworkBlocked, c.connection.value)
        assertEquals(0, sockets.connects.get())

        // The user grants access: the retry (reconnectIfIdle) really connects.
        access.restricted = false
        c.reconnectIfIdle()
        await(c.connection) { it != ConnectionState.LocalNetworkBlocked }
        runBlocking { withTimeout(10_000) { while (sockets.connects.get() == 0) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun `connect loop - split-horizon LAN name times out while restricted - blocked, not retried`() {
        val c = newClient(
            FakeAccess(restricted = true),
            InMemorySettings(initialBaseUrl = "http://tether.home.example", initialCookie = "cookie"),
        )
        c.start()
        await(c.connection) { it == ConnectionState.LocalNetworkBlocked }
        val attempts = sockets.connects.get()
        Thread.sleep(500) // ten reconnect delays
        assertEquals(ConnectionState.LocalNetworkBlocked, c.connection.value)
        assertEquals("no reconnect loop against a blocked network", attempts, sockets.connects.get())
    }

    @Test
    fun `connect loop - remote server outage while unrestricted keeps the normal reconnect loop`() {
        val c = newClient(
            FakeAccess(restricted = false),
            InMemorySettings(initialBaseUrl = "http://tether.example.com", initialCookie = "cookie"),
        )
        c.start()
        runBlocking { withTimeout(10_000) { while (sockets.connects.get() < 5) kotlinx.coroutines.delay(20) } }
        assertTrue(c.connection.value != ConnectionState.LocalNetworkBlocked)
    }

    @Test
    fun `connect loop - restricted and repeated timeouts surface the local-network notice, then stop`() {
        // T0.6: a host the classifier cannot see as local (e.g. a global IPv6
        // address on the Wi-Fi LAN) still times out under the block. A run of
        // timeouts while restricted is read as the block: the notice shows and
        // the loop stops (no hammering), exactly like a known-local host.
        val c = newClient(
            FakeAccess(restricted = true),
            InMemorySettings(initialBaseUrl = "http://tether.example.com", initialCookie = "cookie"),
        )
        c.start()
        await(c.connection) { it == ConnectionState.LocalNetworkBlocked }
        assertEquals(ConnectionTimings.LOCAL_NETWORK_SUSPECT_TIMEOUTS, sockets.connects.get())
    }
}
