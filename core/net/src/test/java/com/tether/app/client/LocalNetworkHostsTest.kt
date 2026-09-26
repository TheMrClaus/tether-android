package com.tether.app.client

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table-driven: the host classifier that decides whether Tether ever asks for ACCESS_LOCAL_NETWORK. */
class LocalNetworkHostsTest {

    private val local = listOf(
        // RFC 1918 edges
        "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.0.1", "192.168.255.255",
        // Link-local, and the emulator's host alias (10/8)
        "169.254.0.1", "169.254.255.254", "10.0.2.2",
        // IPv6 link-local fe80::/10 (upper edge febf), with brackets / zone id / upper case
        "fe80::1", "[fe80::1]", "fe80::1%wlan0", "FE80::ABCD", "febf:ffff::1",
        // IPv6 unique-local fc00::/7 and deprecated site-local fec0::/10
        "fc00::1", "fd12:3456:789a::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fec0::1",
        // IPv4-mapped IPv6, dotted and hex forms of 192.168.1.10
        "::ffff:192.168.1.10", "::ffff:c0a8:10a", "[::ffff:10.0.0.5]",
        // mDNS, and single-label LAN names (trailing root dot tolerated)
        "nas.local", "NAS.LOCAL.", "tether.home.local", "tether", "nas", "printer.", "3232235786",
    )

    private val notLocal = listOf(
        // Just outside the private ranges
        "172.15.255.255", "172.32.0.1", "192.169.0.1", "169.255.0.1", "11.0.0.1",
        // Public
        "8.8.8.8", "1.1.1.1", "203.0.113.5",
        // Loopback, unspecified, CGNAT / Tailscale (VPN: excluded by the platform)
        "127.0.0.1", "127.1.2.3", "0.0.0.0", "100.64.0.1", "100.100.100.100",
        // IPv6 loopback, unspecified, documentation, global, multicast, just outside fe80::/10
        "::1", "[::1]", "::", "2001:db8::1", "2606:4700:4700::1111", "ff02::1", "fe7f::1", "fbff::1",
        "::ffff:8.8.8.8", "::ffff:127.0.0.1",
        // Names
        "localhost", "LOCALHOST", "app.localhost", "tether.example.com", "example.local.com", "local.example",
        // Not strict dotted quads: left to the resolved-address check, never assumed local
        "010.0.0.1", "192.168.1", "256.1.1.1", "192.168.1.1.1",
        // Malformed IPv6 literals
        "1::2::3", "fe80::1::", "fe80:::1", "g::1",
        "", " ", ".",
    )

    @Test
    fun `local hosts are classified local`() {
        val wrong = local.filterNot { LocalNetworkHosts.isLocalHost(it) }
        assertTrue("expected local: $wrong", wrong.isEmpty())
    }

    @Test
    fun `non-local hosts are never classified local`() {
        val wrong = notLocal.filter { LocalNetworkHosts.isLocalHost(it) }
        assertTrue("expected NOT local: $wrong", wrong.isEmpty())
    }

    @Test
    fun `resolved addresses use the same ranges`() {
        fun v4(vararg b: Int) = ByteArray(4) { b[it].toByte() }
        assertTrue(LocalNetworkHosts.isLocalAddress(v4(192, 168, 1, 20)))
        assertTrue(LocalNetworkHosts.isLocalAddress(v4(172, 20, 0, 1)))
        assertFalse(LocalNetworkHosts.isLocalAddress(v4(203, 0, 113, 5)))
        assertFalse(LocalNetworkHosts.isLocalAddress(v4(127, 0, 0, 1)))
        assertTrue(LocalNetworkHosts.isLocalAddress(LocalNetworkHosts.parseIpv6("fd00::7")!!))
        assertFalse(LocalNetworkHosts.isLocalAddress(LocalNetworkHosts.parseIpv6("2001:db8::7")!!))
        assertFalse(LocalNetworkHosts.isLocalAddress(ByteArray(0)))
    }

    @Test
    fun `ipv6 parser follows RFC 4291 text forms`() {
        val cases = mapOf(
            "1:2:3:4:5:6:7:8" to "00010002000300040005000600070008",
            "::" to "0".repeat(32),
            "::1" to "0".repeat(31) + "1",
            "1::" to "0001" + "0".repeat(28),
            "1:2:3:4:5:6::8" to "000100020003000400050006" + "0000" + "0008",
            "::ffff:1.2.3.4" to "0".repeat(20) + "ffff" + "01020304",
            "1:2:3:4:5:6:1.2.3.4" to "000100020003000400050006" + "01020304",
        )
        for ((text, hex) in cases) {
            val parsed = LocalNetworkHosts.parseIpv6(text)
            assertEquals(text, hex, parsed!!.joinToString("") { "%02x".format(it) })
        }
        val invalid = listOf(
            "", "1", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8", "12345::",
            ":1::2", "1::2:", "1::2::3", "::1.2.3", "::1.2.3.256", "1.2.3.4",
        )
        for (text in invalid) assertNull(text, LocalNetworkHosts.parseIpv6(text))
    }

    @Test
    fun `ipv4 parser accepts only strict dotted quads`() {
        assertArrayEquals(byteArrayOf(10, 0, 2, 2), LocalNetworkHosts.parseIpv4("10.0.2.2"))
        for (text in listOf("10.0.2", "10.0.2.2.2", "010.0.2.2", "10.0.2.256", "10..2.2", "a.b.c.d", "1e1.0.0.1", "")) {
            assertNull(text, LocalNetworkHosts.parseIpv4(text))
        }
    }

    // --- denial -> state mapping --------------------------------------------------

    @Test
    fun `transport failures are the documented denial signatures`() {
        // Docs: TCP -> typically a timeout; general denials -> EPERM (a SocketException).
        assertTrue(LocalNetworkDenial.isTransportFailure(SocketTimeoutException("connect timed out")))
        assertTrue(LocalNetworkDenial.isTransportFailure(SocketException("connect failed: EPERM (Operation not permitted)")))
        assertTrue(LocalNetworkDenial.isTransportFailure(ConnectException("Failed to connect to /192.168.1.20:8080")))
        assertTrue(LocalNetworkDenial.isTransportFailure(NoRouteToHostException("No route to host")))
        assertTrue(LocalNetworkDenial.isTransportFailure(IOException("wrapped", SocketTimeoutException("timeout"))))
    }

    @Test
    fun `failures that prove the server answered are never read as a permission block`() {
        assertFalse(LocalNetworkDenial.isTransportFailure(IOException("healthz returned HTTP 500")))
        assertFalse(LocalNetworkDenial.isTransportFailure(IOException("auth probe returned HTTP 502")))
        assertFalse(LocalNetworkDenial.isTransportFailure(UnknownHostException("nas.example.test")))
    }

    @Test
    fun `blocked only when restricted AND local AND a transport failure`() {
        val timeout = SocketTimeoutException("connect timed out")
        val http = IOException("healthz returned HTTP 500")
        assertTrue(LocalNetworkDenial.isBlockedByPermission(restricted = true, targetIsLocal = true, error = timeout))
        // API < 37, or permission granted: an ordinary outage.
        assertFalse(LocalNetworkDenial.isBlockedByPermission(restricted = false, targetIsLocal = true, error = timeout))
        // Remote server: never the permission.
        assertFalse(LocalNetworkDenial.isBlockedByPermission(restricted = true, targetIsLocal = false, error = timeout))
        // The server answered.
        assertFalse(LocalNetworkDenial.isBlockedByPermission(restricted = true, targetIsLocal = true, error = http))
    }
}
