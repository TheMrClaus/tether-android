package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// Settings → Advanced → "Browser": the server's browser runtime status, the web's BrowserRuntimeSection
// (components/settings-dialog.tsx, Browser section; lib/browser-runtime.ts `browserRuntimeReport`).
//   GET /api/browser/runtime-status   (owner grade: server.mjs `requireOwnerGrade`)
//   200 { usable, playwrightImportable, playwrightVersion, chromiumRevision, executablePath,
//         binaryPresent, importError?, installCommand, canInstall?, location? }
//   POST /api/browser/install         (owner grade; never automatic: only the Install Chromium key sends it)
//   200 { ok: true, status: job }  |  409 { ok: false, code: "already_installing" | "playwright_missing", message, status: job }
//   GET  /api/browser/install         job = { state: idle|running|done|failed, startedAt, finishedAt, exitCode, reason, output[] }
// A read: nothing is launched or installed. A refused owner-grade call answers 403 "This needs an owner
// sign-in …"; a server from before the route answers 404. Every call goes through [FixedRouteHttp]:
// redirects off, a fixed path on the paired origin, and only for the origin the screen drew from.
// ─────────────────────────────────────────────────────────────────────────────

/** The route's answer: the detector's facts, whether the browser can open, and the one command when something is missing. */
data class BrowserRuntimeStatus(
    val usable: Boolean,
    val playwrightImportable: Boolean,
    val playwrightVersion: String?,
    val chromiumRevision: String?,
    val executablePath: String?,
    val binaryPresent: Boolean,
    val importError: String?,
    /** Null when the browser is usable. */
    val installCommand: String?,
    /** True when the Install Chromium key may show (Playwright loads, its Chromium is missing); null from a server older than the key. */
    val canInstall: Boolean? = null,
    /** "state" (installed by Tether), "default" or null; null from an older server. */
    val location: String? = null,
)

/** One install job's progress (lib/browser-install.ts `BrowserInstallState`). [state]: idle, running, done or failed. */
data class BrowserInstallJob(
    val state: String,
    val startedAt: Long?,
    val finishedAt: Long?,
    val exitCode: Int?,
    val reason: String?,
    val output: List<String>,
) {
    val running: Boolean get() = state == "running"

    companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/**
 * The answer to POST /api/browser/install: [ok] true (200, the job started or already ended) or false (409:
 * [code] `already_installing` / `playwright_missing` with the server's [message]). [job] is the server's `status`.
 */
data class BrowserInstallStart(val ok: Boolean, val code: String?, val message: String?, val job: BrowserInstallJob?)

/** The one call of the section, for the server [origin] the screen drew from. */
interface BrowserRuntimeSource {
    suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus>

    /** POST /api/browser/install: starts the one install job. Only the owner's press calls this. */
    suspend fun startInstall(origin: String): SecurityResult<BrowserInstallStart> = SecurityResult.SignedOut()

    /** GET /api/browser/install: the job's progress. */
    suspend fun installJob(origin: String): SecurityResult<BrowserInstallJob> = SecurityResult.SignedOut()

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : BrowserRuntimeSource {
        override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> = SecurityResult.SignedOut()
        override suspend fun startInstall(origin: String): SecurityResult<BrowserInstallStart> = SecurityResult.SignedOut()
        override suspend fun installJob(origin: String): SecurityResult<BrowserInstallJob> = SecurityResult.SignedOut()
    }

    companion object {
        const val STATUS_PATH = "/api/browser/runtime-status"
        const val INSTALL_PATH = "/api/browser/install"

        /** The answer is a few hundred bytes. */
        const val MAX_BODY_BYTES: Long = 64L * 1024L

        /** The server imports Playwright and stats one path; no browser is launched. */
        const val CALL_TIMEOUT_MS: Long = 20_000L
    }
}

/** [BrowserRuntimeSource] over [FixedRouteHttp], with the client's per-call (server, credential) read. */
class HttpBrowserRuntime(
    http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    maxBytes: Long = BrowserRuntimeSource.MAX_BODY_BYTES,
    callTimeoutMs: Long = BrowserRuntimeSource.CALL_TIMEOUT_MS,
) : BrowserRuntimeSource {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)

    // settings-dialog.tsx `fetch("/api/browser/runtime-status", { cache: "no-store" })`.
    override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> =
        call(origin, FixedRouteHttp.Method.GET, BrowserRuntimeSource.STATUS_PATH, BrowserRuntimeJson::status)

    // settings-dialog.tsx `fetch("/api/browser/install", { method: "POST" })`: a 409 carries the server's own message.
    override suspend fun startInstall(origin: String): SecurityResult<BrowserInstallStart> =
        call(origin, FixedRouteHttp.Method.POST, BrowserRuntimeSource.INSTALL_PATH, BrowserRuntimeJson::start, also = setOf(409))

    // settings-dialog.tsx `fetch("/api/browser/install", { cache: "no-store" })`.
    override suspend fun installJob(origin: String): SecurityResult<BrowserInstallJob> =
        call(origin, FixedRouteHttp.Method.GET, BrowserRuntimeSource.INSTALL_PATH, BrowserRuntimeJson::job)

    private suspend fun <T> call(
        origin: String,
        method: FixedRouteHttp.Method,
        path: String,
        parse: (JsonObject) -> T?,
        also: Set<Int> = emptySet(),
    ): SecurityResult<T> =
        when (val out = route.call(authority(), origin, method, path, null)) {
            FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
            FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
            is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
            is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
            is FixedRouteHttp.Outcome.Answered -> answered(out, parse, also)
        }

    private fun <T> answered(out: FixedRouteHttp.Outcome.Answered, parse: (JsonObject) -> T?, also: Set<Int>): SecurityResult<T> {
        val json = out.json
        val error = json?.let(DeviceSecurityJson::errorSentence)
        return when {
            out.code in 200..299 || out.code in also -> json?.let(parse)?.let { SecurityResult.Ok(it, out.origin, null) } ?: SecurityResult.Unavailable(out.code, out.origin)
            out.code == 401 && out.jsonType -> SecurityResult.SignedOut(out.origin)
            out.code == 403 && error != null && error.startsWith(DeviceSecuritySource.OWNER_REFUSAL_OPENING) -> SecurityResult.OwnerSignInNeeded(out.origin)
            json != null -> SecurityResult.Refused(out.code, error.orEmpty(), out.origin)
            else -> SecurityResult.Unavailable(out.code, out.origin)
        }
    }
}

/** The tolerant, bounded read of the answer: a field of the wrong type is absent; every string is cut before it is kept. */
object BrowserRuntimeJson {
    const val MAX_CODE = 64
    const val MAX_TEXT = 200
    const val MAX_PATH = 1024

    /** Null without a boolean `usable`: not the route's answer (a gateway's `{"error": …}` at 200), so it never replaces a good status. */
    fun status(o: JsonObject): BrowserRuntimeStatus? {
        val usable = bool(o["usable"]) ?: return null
        return BrowserRuntimeStatus(
            usable = usable,
            playwrightImportable = bool(o["playwrightImportable"]) == true,
            playwrightVersion = string(o["playwrightVersion"], MAX_CODE)?.takeIf { it.isNotBlank() },
            chromiumRevision = string(o["chromiumRevision"], MAX_CODE)?.takeIf { it.isNotBlank() },
            executablePath = string(o["executablePath"], MAX_PATH)?.takeIf { it.isNotBlank() },
            binaryPresent = bool(o["binaryPresent"]) == true,
            importError = string(o["importError"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            installCommand = string(o["installCommand"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            canInstall = bool(o["canInstall"]),
            location = string(o["location"], MAX_CODE)?.takeIf { it == "state" || it == "default" },
        )
    }

    const val MAX_LINES = 40
    const val MAX_LINE = 400
    private val STATES = setOf(BrowserInstallJob.IDLE, BrowserInstallJob.RUNNING, BrowserInstallJob.DONE, BrowserInstallJob.FAILED)

    /** Null without a known `state`: not the route's answer, so it never replaces a job already shown. Output is the last [MAX_LINES] lines, each cut. */
    fun job(o: JsonObject): BrowserInstallJob? {
        val state = string(o["state"], MAX_CODE)?.takeIf { it in STATES } ?: return null
        val lines = (o["output"] as? JsonArray)?.mapNotNull { string(it, MAX_LINE) }.orEmpty().takeLast(MAX_LINES)
        return BrowserInstallJob(
            state = state,
            startedAt = long(o["startedAt"]),
            finishedAt = long(o["finishedAt"]),
            exitCode = long(o["exitCode"])?.toInt(),
            reason = string(o["reason"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            output = lines,
        )
    }

    /** Null without a boolean `ok`. A 409's `code` / `message` are kept (the message is the server's sentence, shown as the web shows it). */
    fun start(o: JsonObject): BrowserInstallStart? {
        val ok = bool(o["ok"]) ?: return null
        return BrowserInstallStart(
            ok = ok,
            code = string(o["code"], MAX_CODE),
            message = string(o["message"], MAX_TEXT)?.takeIf { it.isNotBlank() },
            job = (o["status"] as? JsonObject)?.let(::job),
        )
    }

    private fun long(element: JsonElement?): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toLongOrNull()
    }

    private fun string(element: JsonElement?, max: Int): String? {
        val p = element as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return TextCut.cut(p.content, max)
    }

    private fun bool(element: JsonElement?): Boolean? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}
