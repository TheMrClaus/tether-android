package com.tether.app.client

import java.util.Base64
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * T8.6: the in-console browser's `/ws-browser` channel, as the web's hooks/use-browser.ts (90fbb9f)
 * drives it.
 *
 * The channel is OFF the main protocol (server.mjs 90fbb9f :1253-1258, D6): JSON text frames on a
 * socket of their own, opened as `/ws-browser?sessionId=<id>` and authenticated exactly like `/ws`
 * (server.mjs :8745-8765: the same principal, the same Origin rule for an ambient credential). The
 * app opens it through [BrowserSocketOpener], which the client builds with the very credential,
 * console Origin and transport its `/ws` upgrade uses.
 *
 * Client → server (use-browser.ts :216-232): `open` (sent on connect, :130), `navigate {url}`,
 * `viewport {width,height,mobile,deviceScaleFactor}`, `input {event}`, `pick-mode {on}`,
 * `hover {x,y}`, `pick {x,y,screenshot}`, `close`. A message is sent only while the socket is open,
 * otherwise dropped (:118-121).
 *
 * Server → client (:141-200): `frame {id,data,metadata}` (data: a base64 JPEG screencast frame),
 * `state {url,title,viewport,pickMode,loading}`, `navigated {url,title}`, `hover`, `picked`,
 * `pick-empty`, `closed` (resets the state), `error {message}`. Like the web, nothing reconnects by
 * itself: a new pane (or a new session) opens a new channel.
 */
class BrowserChannel(private val opener: BrowserSocketOpener, val sessionId: String) {
    data class Viewport(val width: Int, val height: Int, val mobile: Boolean, val deviceScaleFactor: Double)

    data class State(
        val url: String = "about:blank",
        val title: String = "",
        val viewport: Viewport = DEFAULT_VIEWPORT,
        val pickMode: Boolean = false,
        val loading: Boolean = false,
    )

    /** use-browser.ts :94-96: `connected`, `state` and `error`, read together. */
    data class Ui(val connected: Boolean = false, val state: State = State(), val error: String? = null)

    /** One decoded screencast frame (`data:image/jpeg;base64,…`, use-browser.ts :115). */
    class Frame(val id: Long, val jpeg: ByteArray)

    private val uiState = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = uiState.asStateFlow()

    private val frameState = MutableStateFlow<Frame?>(null)

    /** The latest frame; a slower reader skips straight to the newest, as the web's one `<img>` does. */
    val frames: StateFlow<Frame?> = frameState.asStateFlow()

    private val pickFlow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * T8.6 part 2's seam: the `hover`, `picked` and `pick-empty` messages verbatim, for Select
     * elements (use-browser.ts :165-186). Part 1 draws no pick UI.
     */
    val pickEvents: SharedFlow<JsonObject> = pickFlow.asSharedFlow()

    private val lock = Any()
    private var socket: BrowserSocket? = null
    private var open = false
    private var disposed = false
    private var frameSeq = 0L

    /** Open the channel (use-browser.ts :124-132); once, until [dispose]. */
    fun connect() {
        synchronized(lock) {
            if (disposed || socket != null) return
        }
        val listener = Listener()
        when (val outcome = opener.open(sessionId, listener)) {
            is BrowserSocketOpen.Opened -> {
                val stale = synchronized(lock) {
                    if (disposed) {
                        true
                    } else {
                        socket = outcome.socket
                        false
                    }
                }
                if (stale) outcome.socket.close()
            }
            is BrowserSocketOpen.Refused -> uiState.update { it.copy(connected = false, error = outcome.message) }
        }
    }

    /** The pane unmounted (use-browser.ts :202-210): close the socket; the server detaches this subscriber. */
    fun dispose() {
        val s = synchronized(lock) {
            disposed = true
            open = false
            socket.also { socket = null }
        }
        s?.close()
        uiState.update { it.copy(connected = false) }
    }

    fun open(url: String? = null) = send(buildJsonObject { put("t", "open"); put("url", if (url != null) JsonPrimitive(url) else JsonNull) })

    fun navigate(url: String) = send(buildJsonObject { put("t", "navigate"); put("url", url) })

    fun setViewport(width: Int, height: Int, mobile: Boolean, deviceScaleFactor: Int = 1) = send(
        buildJsonObject {
            put("t", "viewport")
            put("width", width)
            put("height", height)
            put("mobile", mobile)
            put("deviceScaleFactor", deviceScaleFactor)
        },
    )

    fun sendInput(event: BrowserInput) = send(buildJsonObject { put("t", "input"); put("event", event.toJson()) })

    /** use-browser.ts :222-229: the local flag flips at once; off clears the hover box (part 2). */
    fun setPickMode(on: Boolean) {
        uiState.update { it.copy(state = it.state.copy(pickMode = on)) }
        send(buildJsonObject { put("t", "pick-mode"); put("on", on) })
    }

    fun hover(x: Int, y: Int) = send(buildJsonObject { put("t", "hover"); put("x", x); put("y", y) })

    fun pick(x: Int, y: Int, screenshot: Boolean) = send(buildJsonObject { put("t", "pick"); put("x", x); put("y", y); put("screenshot", screenshot) })

    fun close() = send(buildJsonObject { put("t", "close") })

    private fun send(message: JsonObject): Boolean {
        val s = synchronized(lock) { if (open) socket else null } ?: return false
        return s.send(message.toString())
    }

    private inner class Listener : BrowserSocketListener {
        private fun current(): Boolean = synchronized(lock) { !disposed }

        override fun onOpen(socket: BrowserSocket) {
            synchronized(lock) {
                if (disposed) return
                this@BrowserChannel.socket = socket
                open = true
            }
            uiState.update { it.copy(connected = true, error = null) }
            socket.send(buildJsonObject { put("t", "open") }.toString())
        }

        override fun onMessage(text: String) {
            if (!current()) return
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (message.string("t")) {
                "frame" -> decodeFrame(message.string("data") ?: "")?.let { bytes ->
                    val id = synchronized(lock) { ++frameSeq }
                    frameState.value = Frame(id, bytes)
                }
                "state" -> uiState.update { ui ->
                    val prev = ui.state
                    ui.copy(
                        state = prev.copy(
                            url = message.jsString("url") ?: prev.url,
                            title = message.jsString("title") ?: prev.title,
                            viewport = (message["viewport"] as? JsonObject)?.let(::viewportOf) ?: prev.viewport,
                            pickMode = message.truthy("pickMode"),
                            loading = message.truthy("loading"),
                        ),
                    )
                }
                "navigated" -> uiState.update { ui ->
                    ui.copy(
                        state = ui.state.copy(
                            url = message.jsString("url") ?: ui.state.url,
                            title = message.jsString("title") ?: ui.state.title,
                            loading = false,
                        ),
                    )
                }
                "hover", "picked", "pick-empty" -> pickFlow.tryEmit(message)
                "closed" -> uiState.update { it.copy(state = State()) }
                "error" -> uiState.update { it.copy(error = message.string("message")?.ifEmpty { null } ?: "browser error") }
                else -> Unit
            }
        }

        override fun onFailure() {
            closed()
            if (current()) uiState.update { it.copy(connected = false, error = CONNECTION_ERROR) }
        }

        override fun onClosed() {
            closed()
            uiState.update { it.copy(connected = false) }
        }

        private fun closed() {
            synchronized(lock) {
                open = false
                socket = null
            }
        }
    }

    companion object {
        /** use-browser.ts :66-72. */
        val DEFAULT_VIEWPORT = Viewport(1024, 768, mobile = false, deviceScaleFactor = 1.0)

        /** use-browser.ts :137 (`ws.onerror`). */
        const val CONNECTION_ERROR = "browser connection error"

        /** The JPEG bytes of a frame's base64 `data`, or null when it is not base64. */
        fun decodeFrame(base64: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }

        private fun viewportOf(o: JsonObject): Viewport = Viewport(
            width = (o["width"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
            height = (o["height"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
            mobile = o.truthy("mobile"),
            deviceScaleFactor = (o["deviceScaleFactor"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
        )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** `String(message.x ?? prev)`: null / absent keeps the previous value; anything else is its text. */
        private fun JsonObject.jsString(key: String): String? = when (val v = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            else -> v.toString()
        }

        /** `Boolean(x)`: JS truthiness of a JSON value. */
        private fun JsonObject.truthy(key: String): Boolean = when (val v: JsonElement? = this[key]) {
            null, JsonNull -> false
            is JsonPrimitive -> when {
                v.isString -> v.content.isNotEmpty()
                v.booleanOrNull != null -> v.booleanOrNull!!
                else -> v.doubleOrNull.let { it != null && it != 0.0 && !it.isNaN() }
            }
            else -> true
        }
    }
}

/**
 * One CDP-style input event, as browser-pane.tsx forwards it (use-browser.ts :52-66
 * `BrowserInputEvent`). Absent fields are left out of the frame, as `JSON.stringify` leaves out
 * `undefined`.
 */
data class BrowserInput(
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val button: String? = null,
    val buttons: Int? = null,
    val clickCount: Int? = null,
    val deltaX: Double? = null,
    val deltaY: Double? = null,
    val modifiers: Int? = null,
    val key: String? = null,
    val code: String? = null,
    val text: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        button?.let { put("button", it) }
        buttons?.let { put("buttons", it) }
        clickCount?.let { put("clickCount", it) }
        deltaX?.let { put("deltaX", it) }
        deltaY?.let { put("deltaY", it) }
        modifiers?.let { put("modifiers", it) }
        key?.let { put("key", it) }
        code?.let { put("code", it) }
        text?.let { put("text", it) }
    }

    companion object {
        /** browser-pane.tsx :22-24 `modifiersOf`: Alt 1, Ctrl 2, Meta 4, Shift 8. */
        fun modifiers(alt: Boolean, ctrl: Boolean, meta: Boolean, shift: Boolean): Int =
            (if (alt) 1 else 0) or (if (ctrl) 2 else 0) or (if (meta) 4 else 0) or (if (shift) 8 else 0)
    }
}

/** One open `/ws-browser` socket. */
interface BrowserSocket {
    /** Queue a text frame; false when the socket can no longer take it. */
    fun send(text: String): Boolean

    /** Close normally (queued frames go first). */
    fun close()
}

/** A socket's callbacks, on its own threads; exactly one of [onFailure] / [onClosed] ends it. */
interface BrowserSocketListener {
    fun onOpen(socket: BrowserSocket)
    fun onMessage(text: String)
    fun onFailure()
    fun onClosed()
}

sealed interface BrowserSocketOpen {
    class Opened(val socket: BrowserSocket) : BrowserSocketOpen

    /** Nothing was opened; [message] says why. */
    data class Refused(val message: String) : BrowserSocketOpen
}

/** Opens `/ws-browser?sessionId=<id>` with the paired server's credential, as the `/ws` upgrade. */
fun interface BrowserSocketOpener {
    fun open(sessionId: String, listener: BrowserSocketListener): BrowserSocketOpen

    companion object {
        const val PATH = "/ws-browser"
        const val SIGNED_OUT = "Sign in to Tether to use the browser."
        const val LOCAL_NETWORK = "Tether can't reach this server: local network access is off for the app."

        val Unavailable = BrowserSocketOpener { _, _ -> BrowserSocketOpen.Refused(SIGNED_OUT) }
    }
}
