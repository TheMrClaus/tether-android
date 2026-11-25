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
class BrowserChannel(
    private val opener: BrowserSocketOpener,
    val sessionId: String,
    /** The agent marker and caption deadlines run on this clock (ms); the web's `Date.now()`. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Viewport(val width: Int, val height: Int, val mobile: Boolean, val deviceScaleFactor: Double)

    data class State(
        val url: String = "about:blank",
        val title: String = "",
        val viewport: Viewport = DEFAULT_VIEWPORT,
        val pickMode: Boolean = false,
        val loading: Boolean = false,
        val tabs: List<Tab> = emptyList(),
        val urlNotice: UrlNotice? = null,
        /** The session's active profile; `null` (absent or `null` on the wire) is the default throwaway profile. */
        val profile: PaneProfile? = null,
    ) {
        /** The active tab (`tabs.find((tab) => tab.active)`), or null. */
        val activeTab: Tab? get() = tabs.firstOrNull { it.active }
    }

    /** The `state` frame's optional `urlNotice` (use-browser.ts BrowserUrlNotice); [host] is shown as sent. */
    data class UrlNotice(val kind: UrlNoticeKind, val host: String, val indirect: Boolean)

    enum class UrlNoticeKind { ConsoleBlocked, NonPublic }

    /** use-browser.ts BrowserTab: one open page, as the `state` frame lists it ([id] is what a tab switch sends). */
    data class Tab(val index: Int, val active: Boolean, val url: String, val title: String, val id: String = "")

    /** browser-profiles-ui.ts readPaneProfile: a profile as the pane socket names it. */
    data class PaneProfile(val id: String, val name: String)

    /**
     * use-browser.ts :94-96: `connected`, `state` and `error`, read together; [profileError] is the
     * server's sentence for a refused profile pick (:258-261), kept apart from [error].
     */
    data class Ui(
        val connected: Boolean = false,
        val state: State = State(),
        val error: String? = null,
        val profileError: String? = null,
    )

    /** One decoded screencast frame (`data:image/jpeg;base64,…`, use-browser.ts :115). */
    class Frame(val id: Long, val jpeg: ByteArray)

    private val uiState = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = uiState.asStateFlow()

    private val agentState = MutableStateFlow(BrowserAgentUi.INITIAL)

    /** What the agent is doing in this browser, reduced from the `agent` frames (lib/browser-agent-ui.ts). */
    val agent: StateFlow<AgentUiState> = agentState.asStateFlow()

    /** Let go of the marker and caption that have run out by now; call at [agentDeadlineMs]. */
    fun expireAgent() {
        val now = clock()
        agentState.update { BrowserAgentUi.expire(it, now) }
    }

    /** Milliseconds until the next agent deadline, or null when nothing will run out. */
    fun agentDeadlineMs(): Long? = BrowserAgentUi.nextDeadline(agentState.value, clock())

    private val frameState = MutableStateFlow<Frame?>(null)

    /** The latest frame; a slower reader skips straight to the newest, as the web's one `<img>` does. */
    val frames: StateFlow<Frame?> = frameState.asStateFlow()

    private val pickFlow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The `hover`, `picked` and `pick-empty` messages verbatim (use-browser.ts :161-186). */
    val pickEvents: SharedFlow<JsonObject> = pickFlow.asSharedFlow()

    private val hoverState = MutableStateFlow<HoverBox?>(null)

    /** use-browser.ts :99, :161-163: the last `hover` reply's box; cleared when pick mode goes off (:225). */
    val hoverBox: StateFlow<HoverBox?> = hoverState.asStateFlow()

    private val pickedFlow = MutableSharedFlow<BrowserPick>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Each `picked` as a [BrowserPick] (use-browser.ts :165-184: numbered `pick-N` per channel, with the
     * element screenshot as an attachment when one came). `pick-empty` is not here: the web ignores it
     * (no case in its switch :141-200; server.mjs :8619 sends it for a tap that resolved no element).
     */
    val picks: SharedFlow<BrowserPick> = pickedFlow.asSharedFlow()

    private val lock = Any()
    private var socket: BrowserSocket? = null
    private var open = false
    private var disposed = false
    private var frameSeq = 0L
    private var pickSeq = 0L
    private var profilePending = false

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

    /** use-browser.ts :222-229: the local flag flips at once; off clears the hover box. */
    fun setPickMode(on: Boolean) {
        uiState.update { it.copy(state = it.state.copy(pickMode = on)) }
        if (!on) hoverState.value = null
        send(buildJsonObject { put("t", "pick-mode"); put("on", on) })
    }

    fun hover(x: Int, y: Int) = send(buildJsonObject { put("t", "hover"); put("x", x); put("y", y) })

    fun pick(x: Int, y: Int, screenshot: Boolean) = send(buildJsonObject { put("t", "pick"); put("x", x); put("y", y); put("screenshot", screenshot) })

    fun close() = send(buildJsonObject { put("t", "close") })

    /** use-browser.ts :314: the operator's switch to another open tab. */
    fun switchTab(id: String) = send(
        buildJsonObject {
            put("t", "input")
            put("event", buildJsonObject { put("type", "tab"); put("action", "switch"); put("id", id) })
        },
    )

    /** use-browser.ts :315-320: an id picks, null returns to the default throwaway profile. */
    fun pickProfile(profileId: String?): Boolean {
        uiState.update { it.copy(profileError = null) }
        val sent = send(buildJsonObject { put("t", "profile"); put("profileId", if (profileId != null) JsonPrimitive(profileId) else JsonNull) })
        synchronized(lock) { profilePending = sent }
        return sent
    }

    /** browser-profiles-ui.ts profileQueryFrame: only report the current pick. */
    fun queryProfile() = send(buildJsonObject { put("t", "profile") })

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
            agentState.value = BrowserAgentUi.INITIAL
            // use-browser.ts :186-188: the profile query goes first, then `open`.
            socket.send(buildJsonObject { put("t", "profile") }.toString())
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
                "agent" -> {
                    val now = clock()
                    agentState.update { BrowserAgentUi.reduce(it, message, now) }
                }
                "profile" -> {
                    val read = readPaneProfile(message["profile"])
                    synchronized(lock) { profilePending = false }
                    uiState.update { ui ->
                        ui.copy(
                            state = if (read is PaneProfileRead.Value) ui.state.copy(profile = read.profile) else ui.state,
                            profileError = null,
                        )
                    }
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
                            tabs = tabsOf(message["tabs"]),
                            urlNotice = urlNoticeOf(message["urlNotice"]),
                            // An absent `profile` is the default throwaway profile (use-browser.ts :217-218).
                            profile = (readPaneProfile(message["profile"]) as? PaneProfileRead.Value)?.profile,
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
                "hover" -> {
                    BrowserPicks.parseHover(message)?.let { hoverState.value = it }
                    pickFlow.tryEmit(message)
                }
                "picked" -> {
                    val seq = synchronized(lock) { pickSeq + 1 }
                    BrowserPicks.parsePicked(message, seq)?.let { pick ->
                        synchronized(lock) { pickSeq = seq }
                        pickedFlow.tryEmit(pick)
                    }
                    pickFlow.tryEmit(message)
                }
                "pick-empty" -> pickFlow.tryEmit(message)
                "closed" -> {
                    // The profile is its own state on the web and survives `closed` (use-browser.ts :253-256).
                    uiState.update { it.copy(state = State(profile = it.state.profile)) }
                    agentState.value = BrowserAgentUi.INITIAL
                }
                "error" -> {
                    val text = message.string("message")
                    val wasPending = synchronized(lock) { profilePending.also { profilePending = false } }
                    if (wasPending) {
                        // The answer to the operator's pick: the server's sentence names the holder (:258-261).
                        val v = message["message"]
                        val sentence = if (v != null && message.truthy("message")) message.jsString("message") else null
                        uiState.update { it.copy(profileError = sentence ?: PROFILE_ERROR_FALLBACK) }
                    } else if (text == CONSOLE_BLOCKED_MESSAGE) {
                        uiState.update { ui ->
                            if (ui.state.urlNotice?.kind == UrlNoticeKind.ConsoleBlocked) ui
                            else ui.copy(state = ui.state.copy(urlNotice = UrlNotice(UrlNoticeKind.ConsoleBlocked, "", false)))
                        }
                    } else {
                        uiState.update { it.copy(error = text?.ifEmpty { null } ?: "browser error") }
                    }
                }
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

        /** use-browser.ts :261: the sentence for a refused pick that came without one. */
        const val PROFILE_ERROR_FALLBACK = "The profile could not be used."

        /** browser-profiles-ui.ts readPaneProfile: `null` is the default; anything not `{id, name}` strings changes nothing. */
        internal fun readPaneProfile(v: JsonElement?): PaneProfileRead {
            if (v == null) return PaneProfileRead.Unchanged
            if (v is JsonNull) return PaneProfileRead.Value(null)
            val o = v as? JsonObject ?: return PaneProfileRead.Unchanged
            val id = o.string("id") ?: return PaneProfileRead.Unchanged
            val name = o.string("name") ?: return PaneProfileRead.Unchanged
            return PaneProfileRead.Value(PaneProfile(id, name))
        }

        /** The error frame text that is the console block (browser-notice.ts CONSOLE_BLOCKED_MESSAGE). */
        const val CONSOLE_BLOCKED_MESSAGE = "This address is the console itself, which the session browser does not open."

        /** use-browser.ts parseUrlNotice: an unknown kind or a non-object is no notice; host capped at 120. */
        internal fun urlNoticeOf(v: JsonElement?): UrlNotice? {
            val o = v as? JsonObject ?: return null
            val kind = when (o.string("kind")) {
                "console_blocked" -> UrlNoticeKind.ConsoleBlocked
                "non_public" -> UrlNoticeKind.NonPublic
                else -> return null
            }
            val host = (o.jsString("host") ?: "").take(120)
            return UrlNotice(kind, host, (o["indirect"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } ?: false)
        }

        /** `Array.isArray(message.tabs) ? tabs : []`; an entry that is not an object is skipped. */
        private fun tabsOf(v: JsonElement?): List<Tab> = (v as? kotlinx.serialization.json.JsonArray)
            ?.mapIndexedNotNull { i, e ->
                val o = e as? JsonObject ?: return@mapIndexedNotNull null
                Tab(
                    id = o.jsString("id") ?: "",
                    index = (o["index"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: (i + 1),
                    active = o.truthy("active"),
                    url = o.jsString("url") ?: "",
                    title = o.jsString("title") ?: "",
                )
            }
            ?: emptyList()

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

/** The three answers of `readPaneProfile`: no change (`undefined`), a profile, or the default (`null`). */
internal sealed interface PaneProfileRead {
    data object Unchanged : PaneProfileRead
    data class Value(val profile: BrowserChannel.PaneProfile?) : PaneProfileRead
}

/** lib/browser-agent-ui.ts AgentBox: where an action lands, CSS px of the page's viewport (size 0 for a point). */
data class AgentBox(val x: Double, val y: Double, val width: Double, val height: Double)

data class AgentMarker(val seq: Int, val kind: String, val box: AgentBox, /** The time (ms) the marker is gone. */ val until: Long)

/** lib/browser-agent-ui.ts AgentUiState. */
data class AgentUiState(
    /** An agent action is in flight, or ended less than [BrowserAgentUi.CAPTION_MIN_MS] after it began. */
    val running: Boolean = false,
    val kind: String? = null,
    val startedAt: Long = 0,
    /** While the action has ended but the caption is held to its minimum time: the time it lets go. */
    val settleAt: Long? = null,
    val marker: AgentMarker? = null,
    /** The operator holds the browser: the agent is paused until they stop. */
    val tookOver: Boolean = false,
    /** The sentence for the screen-reader region; changes only when something worth saying happened. */
    val announce: String = "",
    val announcedAt: Long = 0,
    val seq: Int = 0,
)

/** The status line's caption (lib/browser-agent-ui.ts agentCaption). */
data class AgentCaption(val tookOver: Boolean, val text: String, val title: String, val sentence: String)

/** A marker's place over the page frame, as percentages of the viewport (markerPlacement). */
data class MarkerPlacement(val left: Double, val top: Double, val width: Double, val height: Double, val point: Boolean)

/** lib/browser-agent-ui.ts, pure: the channel owns the clock. */
object BrowserAgentUi {
    const val MARKER_MS = 1600L
    const val CAPTION_MIN_MS = 900L
    const val ANNOUNCE_GAP_MS = 8000L

    const val AGENT_CAPTION = "Agent"
    const val AGENT_SENTENCE = "Agent is using the browser"
    const val TOOK_OVER_CAPTION = "You took over"
    const val TOOK_OVER_DETAIL = "You took over the browser. The agent waits until you stop, then it can carry on."
    const val RESUMED_ANNOUNCEMENT = "The agent can use the browser again."
    const val AGENT_ANNOUNCEMENT = "The agent is using the browser."

    val INITIAL = AgentUiState()

    private fun JsonElement?.finiteNumber(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    /** The frame's box, only when every number is real and the size is not negative. */
    fun readBox(value: JsonElement?): AgentBox? {
        val o = value as? JsonObject ?: return null
        val x = o["x"].finiteNumber() ?: return null
        val y = o["y"].finiteNumber() ?: return null
        val w = o["width"].finiteNumber() ?: return null
        val h = o["height"].finiteNumber() ?: return null
        if (w < 0 || h < 0) return null
        return AgentBox(x, y, w, h)
    }

    /** Apply one `agent` frame at time [now]. An unknown phase or a malformed frame changes nothing. */
    fun reduce(state: AgentUiState, frame: JsonObject, now: Long): AgentUiState {
        val phase = (frame["phase"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (phase) {
            "start" -> {
                val kind = (frame["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(40) ?: "action"
                val box = readBox(frame["box"])
                val seq = state.seq + 1
                val fresh = !state.running && now - state.announcedAt > ANNOUNCE_GAP_MS
                val started = state.copy(
                    running = true,
                    kind = kind,
                    startedAt = now,
                    settleAt = null,
                    seq = seq,
                    // While the operator holds the browser nothing is drawn for the agent.
                    marker = if (box != null && !state.tookOver) AgentMarker(seq, kind, box, now + MARKER_MS) else null,
                )
                if (fresh && !state.tookOver) started.copy(announce = AGENT_ANNOUNCEMENT, announcedAt = now) else started
            }
            "end" -> {
                if (!state.running) return state
                val holdUntil = state.startedAt + CAPTION_MIN_MS
                if (now >= holdUntil) state.copy(running = false, settleAt = null) else state.copy(settleAt = holdUntil)
            }
            "took_over" -> state.copy(tookOver = true, marker = null, announce = TOOK_OVER_DETAIL, announcedAt = now)
            "resumed" ->
                if (state.tookOver) state.copy(tookOver = false, announce = RESUMED_ANNOUNCEMENT, announcedAt = now) else state
            else -> state
        }
    }

    /** Let go of whatever has run out by [now]. */
    fun expire(state: AgentUiState, now: Long): AgentUiState {
        var next = state
        val marker = next.marker
        if (marker != null && now >= marker.until) next = next.copy(marker = null)
        val settle = next.settleAt
        if (next.running && settle != null && now >= settle) next = next.copy(running = false, settleAt = null)
        return next
    }

    /** Milliseconds until the next thing in [state] runs out, or null when nothing will. */
    fun nextDeadline(state: AgentUiState, now: Long): Long? {
        val times = listOfNotNull(state.marker?.until, state.settleAt.takeIf { state.running })
        return times.minOrNull()?.let { maxOf(0L, it - now) }
    }

    private fun kindWords(kind: String): String = kind.replace('_', ' ').let { if (it == "tabs") "tab list" else it }

    /** The took-over state wins over "agent is using the browser"; null when neither shows. */
    fun caption(state: AgentUiState): AgentCaption? = when {
        state.tookOver -> AgentCaption(true, TOOK_OVER_CAPTION, TOOK_OVER_DETAIL, TOOK_OVER_DETAIL)
        state.running -> AgentCaption(
            false,
            AGENT_CAPTION,
            state.kind?.takeIf { it.isNotEmpty() }?.let { "$AGENT_SENTENCE (${kindWords(it)})" } ?: AGENT_SENTENCE,
            AGENT_SENTENCE,
        )
        else -> null
    }

    /** A marker's place over the page frame as percentages of the viewport (a point is drawn with a minimum size by the pane). */
    fun placement(box: AgentBox, viewportWidth: Int, viewportHeight: Int): MarkerPlacement {
        val vw = maxOf(1, viewportWidth).toDouble()
        val vh = maxOf(1, viewportHeight).toDouble()
        val point = box.width < 2 && box.height < 2
        fun pct(n: Double, of: Double) = (n / of * 100).coerceIn(0.0, 100.0)
        return MarkerPlacement(pct(box.x, vw), pct(box.y, vh), if (point) 0.0 else pct(box.width, vw), if (point) 0.0 else pct(box.height, vh), point)
    }
}
