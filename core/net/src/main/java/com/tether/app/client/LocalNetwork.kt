package com.tether.app.client

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Android 17 local network protection, as the network layer sees it.
 *
 * An app that targets API 37 has its traffic to the local network blocked unless
 * the user grants the ACCESS_LOCAL_NETWORK runtime permission
 * (developer.android.com/privacy-and-security/local-network-permission). Tether
 * servers are often self-hosted on the LAN, so the client has to know when that
 * block applies instead of reporting a generic "can't connect".
 *
 * This is only a seam: the Android implementation (API level, target SDK,
 * permission check) lives in :app, so :core:net stays free of Android UI and
 * permission APIs. The OS enforces the block. Everything here only decides when
 * to ask for the permission and how to read a failure. It never grants or widens
 * access.
 */
fun interface LocalNetworkAccess {
    /**
     * True when the platform currently blocks this app's local-network traffic:
     * running on API 37+, targeting 37+, and ACCESS_LOCAL_NETWORK not granted.
     * Read again on every connect attempt, because the user can grant or revoke
     * the permission at any time.
     */
    fun isRestricted(): Boolean

    companion object {
        /** No local-network gating: API < 37, and plain JVM tests. */
        val Unrestricted: LocalNetworkAccess = LocalNetworkAccess { false }
    }
}

/**
 * Decides whether a server host is on the local network, without Android APIs
 * and without DNS. [isLocalHost] never resolves anything, so it is safe to call
 * on any thread.
 *
 * The platform defines "local network" by interface ("an IP network that
 * utilizes a broadcast-capable network interface, such as Wi-Fi or Ethernet, but
 * excludes cellular (WWAN) or VPN connections" -- Manifest.permission
 * .ACCESS_LOCAL_NETWORK reference). A URL cannot name an interface, so this uses
 * the address ranges a LAN server sits on, plus the one name rule the docs state
 * (".local" resolution is gated):
 *
 * - IPv4: 10/8, 172.16/12, 192.168/16 (RFC 1918), 169.254/16 (link-local).
 * - IPv6: fe80::/10 (link-local), fc00::/7 (ULA), fec0::/10 (deprecated
 *   site-local), and IPv4-mapped ::ffff:a.b.c.d classified by its IPv4 part.
 * - Names: "*.local" (mDNS), and single-label names ("tether", "nas"), which
 *   only resolve through a LAN search domain.
 *
 * NOT local: loopback (127/8, ::1, "localhost", "*.localhost"), because it is
 * not a broadcast-capable interface; 100.64/10 (CGNAT, the Tailscale range,
 * normally reached over a VPN, which the platform excludes); every public
 * address and every multi-label name. A multi-label name that resolves to a LAN
 * address (split-horizon DNS) is caught after a failed connect by
 * [isLocalAddress] on the resolved addresses. See RealTetherClient.
 */
object LocalNetworkHosts {

    /** [host] as OkHttp's HttpUrl.host gives it; brackets and a zone id are tolerated. */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (':' in h) {
            // Only IPv6 literals contain ':'. A zone id ("%wlan0") only ever
            // qualifies a link-local address, but classify the address itself.
            val address = parseIpv6(h.substringBefore('%')) ?: return false
            return isLocalAddress(address)
        }
        parseIpv4(h)?.let { return isLocalAddress(it) }
        if (h == "localhost" || h.endsWith(".localhost")) return false
        if (h.endsWith(".local")) return true
        return '.' !in h
    }

    /** [address] is a raw 4-byte (IPv4) or 16-byte (IPv6) address, e.g. InetAddress.address. */
    fun isLocalAddress(address: ByteArray): Boolean = when (address.size) {
        4 -> isLocalIpv4(address)
        16 -> isLocalIpv6(address)
        else -> false
    }

    private fun isLocalIpv4(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and 0xff
        val b1 = a[1].toInt() and 0xff
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168) ||
            (b0 == 169 && b1 == 254)
    }

    private fun isLocalIpv6(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and 0xff
        val b1 = a[1].toInt() and 0xff
        // IPv4-mapped ::ffff:a.b.c.d is the IPv4 address on the wire.
        if ((0 until 10).all { a[it].toInt() == 0 } && (a[10].toInt() and 0xff) == 0xff && (a[11].toInt() and 0xff) == 0xff) {
            return isLocalIpv4(a.copyOfRange(12, 16))
        }
        return (b0 == 0xfe && (b1 and 0xc0) == 0x80) || // fe80::/10 link-local
            (b0 == 0xfe && (b1 and 0xc0) == 0xc0) || // fec0::/10 site-local (deprecated)
            (b0 and 0xfe) == 0xfc // fc00::/7 unique local
    }

    /**
     * Strict dotted quad only: four decimal parts, 0..255, no leading zeros. A
     * resolver may read the other inet_aton forms ("010.1", "0x0a...") as octal
     * or hex, so those are NOT treated as literals here. They fall through to the
     * name rules, and the post-failure resolved-address check covers them.
     */
    internal fun parseIpv4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    /** RFC 4291 text form: '::' compression and an optional trailing dotted quad. No zone id. */
    internal fun parseIpv6(input: String): ByteArray? {
        if (input.isEmpty()) return null
        var text = input
        var v4Tail: ByteArray? = null
        val lastColon = text.lastIndexOf(':')
        if (lastColon < 0) return null
        if ('.' in text.substring(lastColon + 1)) {
            v4Tail = parseIpv4(text.substring(lastColon + 1)) ?: return null
            // Stand in two zero groups for the 32 bits the dotted quad fills.
            text = text.substring(0, lastColon + 1) + "0:0"
        }
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
        val head = groups(if (gap >= 0) text.substring(0, gap) else text) ?: return null
        val tail = if (gap >= 0) groups(text.substring(gap + 2)) ?: return null else emptyList()
        val words = when {
            gap < 0 -> if (head.size == 8) head else return null
            head.size + tail.size > 7 -> return null
            else -> head + List(8 - head.size - tail.size) { 0 } + tail
        }
        val out = ByteArray(16)
        for ((i, word) in words.withIndex()) {
            out[2 * i] = (word shr 8).toByte()
            out[2 * i + 1] = word.toByte()
        }
        v4Tail?.copyInto(out, destinationOffset = 12)
        return out
    }

    private fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { group ->
            if (group.length !in 1..4 || !group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            group.toInt(16)
        }
    }
}

/**
 * How the OS local-network block shows up to OkHttp. The docs say: "TCP
 * Connections will typically result in a timeout error" and "UDP errors and
 * general permission denials will typically result in an EPERM error code",
 * which surfaces as a SocketException. Only transport-level failures count. A
 * failure that proves the server answered (an HTTP status the client rejected,
 * a bad body) is never read as a permission block.
 */
object LocalNetworkDenial {
    fun isTransportFailure(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 8) {
            if (e is SocketTimeoutException || e is SocketException) return true
            e = e.cause
            depth++
        }
        return false
    }

    /**
     * The whole decision, as a pure function: is this failed connect attempt the
     * local-network permission, not an ordinary outage? [targetIsLocal] is
     * [LocalNetworkHosts.isLocalHost] for the URL host, or a local resolved
     * address.
     */
    fun isBlockedByPermission(restricted: Boolean, targetIsLocal: Boolean, error: IOException): Boolean =
        restricted && targetIsLocal && isTransportFailure(error)
}
