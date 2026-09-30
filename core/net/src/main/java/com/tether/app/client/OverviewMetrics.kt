package com.tether.app.client

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// ─────────────────────────────────────────────────────────────────────────────
// T15.3: the Overview's "Host & usage" readings (v131, OVERVIEW_STUDIO_PLAN.md §6):
//   GET /api/overview/host   (server.mjs, lib/host-metrics.mjs `snapshot()`)
//   GET /api/overview/usage  (server.mjs, lib/usage-daily.mjs `summarizeDaily`)
// Both sit behind the ordinary /api/ gate (any credential; a 401 JSON without one) and are in the
// tether README's gateway exempt list. Shapes: lib/protocol.ts `HostMetricsSnapshot`,
// `OverviewUsageSummary`. Only the fields the web tile draws (components/overview/overview-host.tsx)
// are read; everything else is ignored.
// ─────────────────────────────────────────────────────────────────────────────

/** One host reading: a measured value, or the server's `{ unavailable: reason }` (never a false 0%). */
sealed interface HostReading<out T> {
    data class Value<T>(val value: T) : HostReading<T>

    /**
     * [reason] is the server's code (`warming_up`, `timeout`, …), cut to [OverviewMetricsJson.MAX_REASON];
     * it is only ever LOOKED UP in a fixed table, never drawn. "" = a reading this client could not use.
     */
    data class Unavailable(val reason: String) : HostReading<Nothing>
}

data class HostCpu(val percent: Double, val cores: Int?)
data class HostMemory(val usedBytes: Double, val totalBytes: Double)
data class HostDisk(val usedBytes: Double, val totalBytes: Double, val availableBytes: Double)

/** `HostMetricsSnapshot`, the parts the tile draws. [scopeLabel] is raw server text (bounded): draw it by the label rule. */
data class HostMetrics(
    val scopeLabel: String?,
    val cpu: HostReading<HostCpu>,
    val memory: HostReading<HostMemory>,
    val disk: HostReading<HostDisk>,
    val stale: Boolean,
)

/** One `coverage` entry: [provider] is raw server text (bounded); [status] `exact` / `partial` / `not_reported` (or unknown). */
data class UsageCoverage(val provider: String, val status: String)

/**
 * `OverviewUsageSummary`, the parts the tile draws. [tokensToday] null = "Not reported" (no harness
 * with day-attributable usage). [label] is raw server text (bounded): draw it by the label rule.
 */
data class OverviewUsage(
    val tokensToday: Double?,
    val label: String,
    val partial: Boolean,
    val coverage: List<UsageCoverage>,
)

/**
 * What one GET came to. [origin] is the paired origin (scheme://host[:port]) the call was made
 * for, on every outcome that got that far: a screen keeps a reading only while the answers keep
 * coming from the origin it came from, so one server's numbers never stand beside another's
 * answer. Null only when there was no server to ask.
 */
sealed interface OverviewMetricsResult<out T> {
    val origin: String?

    data class Ok<T>(val value: T, override val origin: String) : OverviewMetricsResult<T>

    /** No credential, or Tether's own 401 (the /api/ gate's JSON answer). */
    data class SignedOut(override val origin: String? = null) : OverviewMetricsResult<Nothing>

    /** Tether's own 403 (JSON, no challenge). */
    data class Forbidden(override val origin: String) : OverviewMetricsResult<Nothing>

    data object LocalNetworkBlocked : OverviewMetricsResult<Nothing> {
        override val origin: String? get() = null
    }

    /**
     * T6.8's rule ([HttpToolMedia.blockedBySignIn]): a sign-in gateway answered instead of Tether (any
     * 3xx, a 401/403 that is not Tether's JSON or carries a challenge, a 200 HTML page). Decided from
     * the status line and headers only: the body is never kept and a redirect is never followed.
     */
    data class Blocked(val code: Int, override val origin: String) : OverviewMetricsResult<Nothing>

    /** Unreachable, any other non-2xx, a body over the cap, or a body this client cannot use. */
    data class Unavailable(val code: Int?, override val origin: String) : OverviewMetricsResult<Nothing>
}

/** The two readings, fetched with the paired credential. */
interface OverviewMetricsSource {
    suspend fun host(): OverviewMetricsResult<HostMetrics>
    suspend fun usage(): OverviewMetricsResult<OverviewUsage>

    /** No client (previews, fakes): nothing is ever fetched. */
    object Unavailable : OverviewMetricsSource {
        override suspend fun host(): OverviewMetricsResult<HostMetrics> = OverviewMetricsResult.SignedOut()
        override suspend fun usage(): OverviewMetricsResult<OverviewUsage> = OverviewMetricsResult.SignedOut()
    }

    companion object {
        const val HOST_PATH = "/api/overview/host"
        const val USAGE_PATH = "/api/overview/usage"

        /**
         * The most body read. The server's answers are a few hundred bytes (host) and ~2 KiB (usage,
         * six harnesses with their reasons); anything past this is not Tether's and is dropped unread.
         */
        const val MAX_BODY_BYTES: Long = 64L * 1024L

        /** A call that has not finished by now is abandoned (the next poll asks again). */
        const val CALL_TIMEOUT_MS: Long = 15_000L
    }
}

/**
 * [OverviewMetricsSource] over OkHttp, T6.2 / T11.1's pattern ([HttpToolMedia]): [http] MUST NOT
 * follow redirects (OkHttp keeps a hand-set `Cookie` across a cross-host redirect); each call reads
 * the (server, credential) pair together through [authority], builds a FIXED path on that server's
 * origin, re-checks the request is on it before sending, and bounds the body while it streams.
 * Cancelling the caller cancels the socket.
 */
class HttpOverviewMetrics(
    private val http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    // Parameters only so tests can reach the bounds cheaply.
    private val maxBytes: Long = OverviewMetricsSource.MAX_BODY_BYTES,
    private val callTimeoutMs: Long = OverviewMetricsSource.CALL_TIMEOUT_MS,
) : OverviewMetricsSource {

    init {
        require(!http.followRedirects && !http.followSslRedirects) {
            "HttpOverviewMetrics needs a client that never follows redirects (the credential must stay on its origin)"
        }
    }

    override suspend fun host(): OverviewMetricsResult<HostMetrics> = get(OverviewMetricsSource.HOST_PATH, OverviewMetricsJson::host)

    override suspend fun usage(): OverviewMetricsResult<OverviewUsage> = get(OverviewMetricsSource.USAGE_PATH, OverviewMetricsJson::usage)

    private suspend fun <T> get(path: String, parse: (JsonObject) -> T?): OverviewMetricsResult<T> {
        val paired = when (val a = authority()) {
            FilesAuthority.SignedOut -> return OverviewMetricsResult.SignedOut()
            FilesAuthority.LocalNetworkBlocked -> return OverviewMetricsResult.LocalNetworkBlocked
            is FilesAuthority.Paired -> a
        }
        val target = paired.origin.newBuilder().encodedPath(path).query(null).fragment(null).build()
        val request = paired.sign(
            Request.Builder().url(target).header("Accept", "application/json").header("Cache-Control", "no-store"),
        ).get().build()
        val origin = consoleOrigin(paired.origin)
        // Nothing but the fixed route on the paired origin ever carries the credential.
        if (!sameOrigin(request.url, paired.origin) || request.url.encodedPath != path || request.url.query != null) {
            return OverviewMetricsResult.Unavailable(null, origin)
        }
        val call = http.newCall(request)
        call.timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        return try {
            callCancellably(call) { response -> read(response, origin, parse) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            OverviewMetricsResult.Unavailable(null, origin)
        } catch (_: RuntimeException) {
            OverviewMetricsResult.Unavailable(null, origin)
        } catch (_: OutOfMemoryError) {
            OverviewMetricsResult.Unavailable(null, origin)
        }
    }

    private fun <T> read(response: Response, origin: String, parse: (JsonObject) -> T?): OverviewMetricsResult<T> {
        val declaredType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (HttpToolMedia.blockedBySignIn(response.code, declaredType, response.header("WWW-Authenticate") != null)) {
            return OverviewMetricsResult.Blocked(response.code, origin)
        }
        when {
            response.code == 401 -> return OverviewMetricsResult.SignedOut(origin)
            response.code == 403 -> return OverviewMetricsResult.Forbidden(origin)
            response.code != 200 -> return OverviewMetricsResult.Unavailable(response.code, origin)
            // r2: the server's json() always says so (ToolMedia's rule for its own type); a 200 in
            // any other type is not its answer, even if the body parses.
            declaredType != "application/json" -> return OverviewMetricsResult.Unavailable(response.code, origin)
        }
        val text = readCapped(response) ?: return OverviewMetricsResult.Unavailable(response.code, origin)
        val obj = OverviewMetricsJson.parseObject(text) ?: return OverviewMetricsResult.Unavailable(response.code, origin)
        val value = parse(obj) ?: return OverviewMetricsResult.Unavailable(response.code, origin)
        return OverviewMetricsResult.Ok(value, origin)
    }

    /** The body as text, or null when it is (declared or streamed) over [maxBytes]: never more than that is buffered. */
    private fun readCapped(response: Response): String? {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) return null
        val source = body.source()
        // request(n) reads until n bytes are buffered or the body ends: true = there is more than the cap.
        if (source.request(maxBytes + 1)) return null
        return source.buffer.readUtf8()
    }

    private suspend fun <T> callCancellably(call: Call, block: (Response) -> T): T = coroutineScope {
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } finally {
            guard.cancel()
        }
    }

    private companion object {
        fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}

/**
 * The lenient, bounded read of the two bodies. A field of the wrong type is treated as absent; a
 * reading whose numbers break the server's own invariants (negative, non-finite, used over total,
 * a percentage outside 0..100) is shown as unavailable rather than as a false number; every string
 * and array is cut before it is kept.
 */
object OverviewMetricsJson {
    /** Server text kept per string (drawn later by the label rule, which bounds it again). */
    const val MAX_TEXT = 200

    /** A reason / status code: only ever looked up, never drawn. */
    const val MAX_REASON = 32

    /** `coverage` entries kept (the server lists one per harness: six today). */
    const val MAX_COVERAGE = 24

    /** The bodies are two levels deep; anything deeper is not Tether's (and must not reach the parser's stack). */
    private const val MAX_DEPTH = 8

    /** Bytes and token counts above this are not a reading (and would print as hundreds of digits). */
    private const val MAX_COUNT = 1e18

    fun parseObject(text: String): JsonObject? = try {
        if (com.tether.app.protocol.ServerMessage.nestsDeeperThan(text, MAX_DEPTH)) null
        else com.tether.app.protocol.TetherJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /**
     * `HostMetricsSnapshot`. A missing or unusable reading is [HostReading.Unavailable]; null (not the
     * server's answer, r2) when none of `cpu`, `memory`, `disk` is an object, e.g. a gateway's
     * `{"error":"login required"}`: that must not replace a good reading with three blanks.
     */
    fun host(obj: JsonObject): HostMetrics? {
        if (listOf("cpu", "memory", "disk").none { obj[it] is JsonObject }) return null
        return hostReadings(obj)
    }

    private fun hostReadings(obj: JsonObject): HostMetrics = HostMetrics(
        scopeLabel = string(obj["scopeLabel"], MAX_TEXT),
        cpu = reading(obj["cpu"]) { o ->
            val percent = number(o["percent"])?.takeIf { it in 0.0..100.0 } ?: return@reading null
            HostCpu(percent, number(o["cores"])?.takeIf { it >= 1 && it <= 1_000_000 }?.toInt())
        },
        memory = reading(obj["memory"]) { o ->
            val used = count(o["usedBytes"]) ?: return@reading null
            val total = count(o["totalBytes"])?.takeIf { it > 0 } ?: return@reading null
            if (used > total) null else HostMemory(used, total)
        },
        disk = reading(obj["disk"]) { o ->
            val used = count(o["usedBytes"]) ?: return@reading null
            val total = count(o["totalBytes"])?.takeIf { it > 0 } ?: return@reading null
            val available = count(o["availableBytes"]) ?: return@reading null
            if (used > total || available > total) null else HostDisk(used, total, available)
        },
        stale = truthy(obj["stale"]),
    )

    /**
     * `OverviewUsageSummary`: null when `tokensToday` is missing or unusable, or (r2) carries no
     * `value` key at all (the server always sends one, `null` when nothing is reported).
     */
    fun usage(obj: JsonObject): OverviewUsage? {
        val tokens = obj["tokensToday"] as? JsonObject ?: return null
        if ("value" !in tokens) return null
        val raw = tokens["value"]
        val value = when {
            raw == null || raw is JsonNull -> null
            else -> count(raw) ?: return null
        }
        val coverage = (obj["coverage"] as? JsonArray).orEmpty().asSequence()
            .take(MAX_COVERAGE)
            .mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val provider = string(entry["provider"], MAX_TEXT) ?: return@mapNotNull null
                val status = string(entry["status"], MAX_REASON) ?: return@mapNotNull null
                UsageCoverage(provider, status)
            }
            .toList()
        return OverviewUsage(
            tokensToday = value,
            label = string(tokens["label"], MAX_TEXT) ?: "Tokens today",
            partial = (tokens["partial"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true",
            coverage = coverage,
        )
    }

    /** overview-host.tsx `isUnavailable`: `"unavailable" in value`; otherwise [value] must make a reading. */
    private fun <T> reading(element: JsonElement?, value: (JsonObject) -> T?): HostReading<T> {
        val o = element as? JsonObject ?: return HostReading.Unavailable("")
        if ("unavailable" in o) return HostReading.Unavailable(string(o["unavailable"], MAX_REASON).orEmpty())
        return value(o)?.let { HostReading.Value(it) } ?: HostReading.Unavailable("")
    }

    /** A JSON number (not a numeric string), finite. */
    private fun number(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** A byte or token count: a finite number in 0..[MAX_COUNT]. */
    private fun count(element: JsonElement?): Double? = number(element)?.takeIf { it >= 0 && it <= MAX_COUNT }

    /** A JSON string, cut at a cluster boundary to [max] UTF-16 units. */
    private fun string(element: JsonElement?, max: Int): String? {
        val p = element as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return TextCut.cut(p.content, max)
    }

    /** JavaScript truthiness of a primitive (overview-host.tsx reads `data.stale ||`). */
    private fun truthy(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive ?: return element is JsonObject || element is JsonArray
        if (p is JsonNull) return false
        if (p.isString) return p.content.isNotEmpty()
        return when (val c = p.content) {
            "true" -> true
            "false" -> false
            else -> c.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
        }
    }
}
