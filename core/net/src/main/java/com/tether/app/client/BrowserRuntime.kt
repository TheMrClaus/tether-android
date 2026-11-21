package com.tether.app.client

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
//         binaryPresent, importError?, installCommand }
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
)

/** The one call of the section, for the server [origin] the screen drew from. */
interface BrowserRuntimeSource {
    suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : BrowserRuntimeSource {
        override suspend fun status(origin: String): SecurityResult<BrowserRuntimeStatus> = SecurityResult.SignedOut()
    }

    companion object {
        const val STATUS_PATH = "/api/browser/runtime-status"

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
        when (val out = route.call(authority(), origin, FixedRouteHttp.Method.GET, BrowserRuntimeSource.STATUS_PATH, null)) {
            FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
            FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
            is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
            is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
            is FixedRouteHttp.Outcome.Answered -> answered(out)
        }

    private fun answered(out: FixedRouteHttp.Outcome.Answered): SecurityResult<BrowserRuntimeStatus> {
        val json = out.json
        val error = json?.let(DeviceSecurityJson::errorSentence)
        return when {
            out.code in 200..299 -> json?.let(BrowserRuntimeJson::status)?.let { SecurityResult.Ok(it, out.origin, null) } ?: SecurityResult.Unavailable(out.code, out.origin)
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
        )
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
