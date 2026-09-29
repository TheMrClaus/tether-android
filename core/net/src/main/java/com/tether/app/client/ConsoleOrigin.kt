package com.tether.app.client

import okhttp3.HttpUrl

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
