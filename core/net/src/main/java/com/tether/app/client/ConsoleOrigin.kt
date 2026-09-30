package com.tether.app.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The `Origin` this client sends to the server at [server]: `scheme://host[:port]`, the port only
 * when it is not the scheme's default, an IPv6 literal in brackets.
 *
 * The server compares the Origin's host(+port) with `X-Forwarded-Host`, else `Host`, and OkHttp
 * builds `Host` from the same URL the same way (default port omitted, host lowercased and
 * punycoded), so the two always agree for a request addressed to [server]. One builder for the
 * `/ws` upgrade and every cookie-authenticated HTTP request (ta-41x), so they cannot drift.
 */
internal fun consoleOrigin(server: HttpUrl): String = buildString {
    append(server.scheme).append("://").append(bracketedHost(server.host))
    if (server.port != HttpUrl.defaultPort(server.scheme)) append(':').append(server.port)
}

/**
 * A server URL as the operator typed it or the settings store holds it, as this client reads it:
 * trimmed, a trailing slash dropped, `https://` when no scheme is given. Null when unusable. The
 * one normaliser for sign-in, pairing and [serverOrigin], so they cannot drift.
 */
internal fun normalizeServerUrl(raw: String): HttpUrl? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    return withScheme.toHttpUrlOrNull()
}

/**
 * T15.3 r2: the origin ([consoleOrigin]) of a server URL such as [TetherClient.serverUrl], or null
 * when there is none. Compared with a reading's `OverviewMetricsResult.origin` so a screen shows
 * only answers about the server it is showing.
 */
fun serverOrigin(serverUrl: String?): String? = serverUrl?.let(::normalizeServerUrl)?.let(::consoleOrigin)
