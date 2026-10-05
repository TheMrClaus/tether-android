package com.tether.app.client

import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.DirectoryEntry
import com.tether.app.protocol.model.DirectoryListing
import com.tether.app.protocol.ServerMessage
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * T10.6 (ta-jwbs): the first-run wizard's HTTP seam, shaped from tether 90fbb9f lib/setup-server.mjs
 * (the handlers) and app/setup/page.tsx (what the page reads of them). /api/setup routes need no credential
 * (setup mode predates the operator's), so none is ever sent; what is sent goes to the server the user
 * typed and nowhere else. Writes pass setupWriteRefusal -> ambientWriteAllowed (lib/origin-guard.mjs
 * :71-81): a native POST has no Origin, so it is allowed only with `Content-Type: application/json`,
 * bodiless POSTs included. Nothing here logs or keeps a request body: the operator's password travels
 * in `complete`'s only.
 */

/** `GET /api/setup/state` (setup-server.mjs currentState): what the wizard is configured from. */
data class SetupState(
    /** "container" | "systemd" | "native". */
    val runtime: String,
    val dev: Boolean,
    val supportedModes: List<String>,
    /** `envForced[key]`: the environment sets this setting, so the wizard shows it read-only. */
    val envForced: Map<String, Boolean>,
    /** The persisted settings, string-valued ones only (`headlessModes`, `workspaceRoot`, `username`); never a password. */
    val settings: Map<String, String>,
    val hasPassword: Boolean,
    val defaultFolder: String,
    /** Mounted project folders in a container (issue #23); empty elsewhere. */
    val workspaceCandidates: List<String>,
) {
    fun forced(key: String): Boolean = envForced[key] == true
}

/** `POST /api/setup/validate {kind: "engineBinary"}`: `{ok, message?}`. */
data class BinaryCheck(val ok: Boolean, val message: String?)

/** `POST /api/setup/complete` 200: `{ok, runtime, restart}`. */
data class SetupFinish(val restart: String, val runtime: String) {
    val automatic: Boolean get() = restart == "automatic"
}

/** One setup call's outcome. Network and HTTP failures carry the sentence the web would show. */
sealed interface SetupCall<out T> {
    data class Ok<T>(val value: T) : SetupCall<T>

    /**
     * Not answered as asked: [status] is the HTTP status (null = the request did not complete, or
     * local-network access is blocked), [message] the sentence to show.
     */
    data class Failed(val status: Int?, val message: String) : SetupCall<Nothing>
}

/** The wizard's server calls. [HttpSetupApi] is the real one; the goldens and tests hand fakes. */
interface SetupApi {
    /** page.tsx loadState: `GET /api/setup/state`. A 401 is [SetupCall.Failed] with status 401 (setup is already done). */
    suspend fun state(): SetupCall<SetupState>

    /** page.tsx runDetect: `GET /api/setup/detect`. */
    suspend fun detect(): SetupCall<Map<String, EngineDetection>>

    /** page.tsx FolderBrowser: `GET /api/setup/browse?path=`. */
    suspend fun browse(path: String?): SetupCall<DirectoryListing>

    /** page.tsx setLocator: `POST /api/setup/validate {kind: "engineBinary", engine, value}`. */
    suspend fun validateEngineBinary(engine: String, value: String): SetupCall<BinaryCheck>

    /** page.tsx apply: `POST /api/setup/complete {settings}`. */
    suspend fun complete(settings: JsonObject): SetupCall<SetupFinish>

    /**
     * page.tsx's restart poll: `GET /healthz`. True once the server answers OK and is no longer in
     * setup mode; false while it is bouncing or still in setup mode.
     */
    suspend fun configured(): Boolean
}

/** The page's own words for what failed ([HttpSetupApi] and the wizard model use them). */
object SetupCopy {
    const val STATE_FAILED = "The setup service did not answer."
    const val BROWSE_FAILED = "Could not read that folder."
    const val COMPLETE_FAILED = "Setup could not be completed."
    const val BLOCKED = "Local network access is blocked."
}

class HttpSetupApi(
    private val http: OkHttpClient,
    private val base: HttpUrl,
    private val blockedBefore: (HttpUrl) -> Boolean = { false },
    private val blockedAfter: (HttpUrl, IOException) -> Boolean = { _, _ -> false },
    private val callTimeoutSeconds: Long = 60,
) : SetupApi {
    init {
        // The server typed here is the only one these calls may reach (never a redirect elsewhere).
        require(!http.followRedirects && !http.followSslRedirects) { "HttpSetupApi needs a client that never follows redirects" }
    }

    /** The raw seam ta-pqui (GitHub, Claude accounts) builds its routes on: status and JSON body, or why none. */
    class Reply(val status: Int, val body: JsonObject?)

    sealed interface Sent {
        class Got(val reply: Reply) : Sent
        class Down(val message: String) : Sent
    }

    private suspend fun send(request: Request): Sent = withContext(Dispatchers.IO) {
        if (blockedBefore(base)) return@withContext Sent.Down(SetupCopy.BLOCKED)
        try {
            val call = http.newCall(request)
            call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
            call.execute().use { response ->
                // `res.json().catch(() => ({}))`: a body that is not a JSON object reads as no body at all.
                val text = response.peekBody(MAX_BODY_BYTES).string()
                val obj = try {
                    if (ServerMessage.nestsDeeperThan(text, ServerMessage.MAX_FRAME_DEPTH)) null
                    else TetherJson.parseToJsonElement(text) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                Sent.Got(Reply(response.code, obj))
            }
        } catch (e: IOException) {
            if (blockedAfter(base, e)) Sent.Down(SetupCopy.BLOCKED) else Sent.Down(e.message ?: "The server could not be reached.")
        }
    }

    private fun url(path: String, query: Pair<String, String>? = null): HttpUrl {
        val resolved = checkNotNull(base.resolve(path))
        return if (query == null) resolved else resolved.newBuilder().addQueryParameter(query.first, query.second).build()
    }

    suspend fun get(path: String, query: Pair<String, String>? = null): Sent =
        send(Request.Builder().url(url(path, query)).get().build())

    /** Every POST is JSON, an empty one included: the server refuses a no-Origin write that is not. */
    suspend fun post(path: String, body: JsonObject?): Sent =
        send(Request.Builder().url(url(path)).post((body?.toString() ?: "").toRequestBody(JSON)).build())

    private fun Reply.error(): String? = (body?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun <T> Sent.Down.failed(): SetupCall<T> = SetupCall.Failed(null, message)

    override suspend fun state(): SetupCall<SetupState> = when (val sent = get("/api/setup/state")) {
        is Sent.Down -> sent.failed()
        is Sent.Got -> {
            val reply = sent.reply
            val parsed = if (reply.status in 200..299) reply.body?.let(::parseState) else null
            when {
                reply.status == 401 -> SetupCall.Failed(401, reply.error() ?: SetupCopy.STATE_FAILED)
                parsed != null -> SetupCall.Ok(parsed)
                else -> SetupCall.Failed(reply.status, reply.error() ?: SetupCopy.STATE_FAILED)
            }
        }
    }

    override suspend fun detect(): SetupCall<Map<String, EngineDetection>> = when (val sent = get("/api/setup/detect")) {
        is Sent.Down -> sent.failed()
        is Sent.Got -> {
            val detected = sent.reply.body?.get("detected") as? JsonObject
            if (sent.reply.status in 200..299 && detected != null) {
                SetupCall.Ok(
                    // The Settings card's tolerant reader (ta-dh1) reads each engine: one result per engine.
                    detected.entries.mapNotNull { (engine, value) -> EngineDetection.of(value)?.let { engine to it } }.toMap(),
                )
            } else {
                SetupCall.Failed(sent.reply.status, sent.reply.error() ?: "Detection did not answer.")
            }
        }
    }

    override suspend fun browse(path: String?): SetupCall<DirectoryListing> =
        when (val sent = get("/api/setup/browse", path?.takeIf { it.isNotEmpty() }?.let { "path" to it })) {
            is Sent.Down -> sent.failed()
            is Sent.Got -> {
                val reply = sent.reply
                val listing = (reply.body?.get("listing") as? JsonObject)?.let(::parseListing)
                val ok = reply.status in 200..299 && reply.body?.bool("ok") == true && listing != null
                if (ok) SetupCall.Ok(listing!!) else SetupCall.Failed(reply.status, reply.error() ?: SetupCopy.BROWSE_FAILED)
            }
        }

    override suspend fun validateEngineBinary(engine: String, value: String): SetupCall<BinaryCheck> {
        val body = buildJsonObject {
            put("kind", "engineBinary")
            put("engine", engine)
            put("value", value)
        }
        return when (val sent = post("/api/setup/validate", body)) {
            is Sent.Down -> sent.failed()
            is Sent.Got -> {
                val ok = sent.reply.body?.bool("ok")
                if (sent.reply.status in 200..299 && ok != null) {
                    SetupCall.Ok(BinaryCheck(ok, sent.reply.body?.string("message")))
                } else {
                    SetupCall.Failed(sent.reply.status, sent.reply.error() ?: "The check did not answer.")
                }
            }
        }
    }

    override suspend fun complete(settings: JsonObject): SetupCall<SetupFinish> {
        val body = buildJsonObject { put("settings", settings) }
        return when (val sent = post("/api/setup/complete", body)) {
            is Sent.Down -> sent.failed()
            is Sent.Got -> {
                val reply = sent.reply
                if (reply.status in 200..299) {
                    SetupCall.Ok(
                        SetupFinish(
                            restart = reply.body?.string("restart") ?: "manual",
                            runtime = reply.body?.string("runtime") ?: "native",
                        ),
                    )
                } else {
                    // page.tsx apply: the `problems` / `errors` messages joined, else `error`, else the default.
                    val problems = ((reply.body?.get("problems") ?: reply.body?.get("errors")) as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.string("message")?.takeIf(String::isNotEmpty) }
                    SetupCall.Failed(
                        reply.status,
                        if (problems.isNotEmpty()) problems.joinToString(" ") else reply.error() ?: SetupCopy.COMPLETE_FAILED,
                    )
                }
            }
        }
    }

    override suspend fun configured(): Boolean = when (val sent = get("/healthz")) {
        is Sent.Down -> false
        is Sent.Got -> sent.reply.status in 200..299 && sent.reply.body?.bool("setupRequired") != true
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        /** A setup reply is small (a state document, a folder listing); nothing past this is read. */
        const val MAX_BODY_BYTES = 1L shl 20
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
    when (it) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonElement?.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** Null when the reply is not a state document (no supported-modes list: the page would not work from it). */
internal fun parseState(obj: JsonObject): SetupState? {
    if (obj["supportedModes"] !is JsonArray) return null
    val forced = (obj["envForced"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to (it == "true") } }.toMap()
    val settings = (obj["settings"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
    }.toMap()
    return SetupState(
        runtime = obj.string("runtime") ?: "native",
        dev = obj.bool("dev") == true,
        supportedModes = obj["supportedModes"].strings(),
        envForced = forced,
        settings = settings,
        hasPassword = obj.bool("hasPassword") == true,
        defaultFolder = obj.string("defaultFolder") ?: "",
        workspaceCandidates = obj["workspaceCandidates"].strings(),
    )
}

internal fun parseListing(obj: JsonObject): DirectoryListing? {
    val current = obj.string("current") ?: return null
    val entries = (obj["entries"] as? JsonArray).orEmpty().mapNotNull {
        val entry = it as? JsonObject ?: return@mapNotNull null
        DirectoryEntry(name = entry.string("name") ?: return@mapNotNull null, path = entry.string("path") ?: return@mapNotNull null)
    }
    return DirectoryListing(current = current, parent = obj.string("parent"), entries = entries)
}
