package com.tether.app.client

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * T8.6: a `/ws-browser` socket on the app's own [TetherWebSocket], the transport of the `/ws`
 * upgrade (same client, same TLS trust, no redirects), adapted to [BrowserSocketListener]. Every
 * callback is delivered once; a server close ends the socket as the browser's `onclose` does.
 */
internal class OkHttpBrowserSocket private constructor(private val listener: BrowserSocketListener) : BrowserSocket {
    @Volatile private var ws: WebSocket? = null

    @Volatile private var ended = false

    override fun send(text: String): Boolean = ws?.send(text) ?: false

    override fun close() {
        ws?.close(NORMAL_CLOSURE, null)
    }

    private val callbacks = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            listener.onOpen(this@OkHttpBrowserSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!ended) listener.onMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            end { listener.onClosed() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            end { listener.onClosed() }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end { listener.onFailure() }
        }
    }

    private fun end(block: () -> Unit) {
        synchronized(this) {
            if (ended) return
            ended = true
        }
        block()
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        fun connect(client: OkHttpClient, request: Request, listener: BrowserSocketListener): OkHttpBrowserSocket {
            val socket = OkHttpBrowserSocket(listener)
            socket.ws = TetherWebSocket.connect(client, request, socket.callbacks)
            return socket
        }
    }
}
