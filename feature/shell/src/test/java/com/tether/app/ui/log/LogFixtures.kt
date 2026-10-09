package com.tether.app.ui.log

import com.tether.app.client.ServerStats
import com.tether.app.crash.CrashRecord
import com.tether.app.crash.ProcessExit
import com.tether.app.protocol.LogEntry
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.model.AgentSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * Seeded log-dialog states. The info rows are real frames of the vendored wire corpus
 * (parity-corpus/wire, the fake-engine capture); the warn/error rows are hand-written in the
 * server's record shape (obs.mjs), since the capture logged none.
 */
object LogFixtures {
    val locale: Locale = Locale.US
    val zone: ZoneId = ZoneId.of("UTC")

    private val corpusDir = File(System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire")

    /** Every `log` frame of one corpus scenario, decoded in wire order. */
    fun corpusLog(file: String): List<LogEntry> =
        File(corpusDir, file).readLines().filter { it.isNotBlank() }
            .map { TetherJson.parseToJsonElement(it).jsonObject }
            .filter { it["dir"]?.jsonPrimitive?.content == "s2c" }
            .map { it["frame"]!!.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "log" }
            .map { ServerMessage.parse(it) as ServerMessage.Log }
            .flatMap { it.entries }

    fun entry(json: String): LogEntry = LogEntry.fromJson(TetherJson.parseToJsonElement(json) as JsonObject)!!

    private const val T0 = 1_767_225_600_000L // 2026-01-01 00:00:00 UTC, the corpus clock

    val sessions = listOf(
        session("sess-0001", "Fix flaky login test"),
        session("sess-0002", "Refactor billing export"),
    )

    /** A mixed log: turns in two sessions, an unnamed session, a warning and an error. */
    val mixed: List<LogEntry> = listOf(
        entry("""{"seq":1,"ts":${T0 + 1_000},"level":"info","event":"ws.connect","connId":1,"principal":"cookie","clients":1}"""),
        entry("""{"seq":2,"ts":${T0 + 5_000},"level":"info","event":"turn.start","sid":"sess-0001","turnId":"00000000-0000-4000-8000-000000000001","nativeId":null,"continuation":false}"""),
        entry("""{"seq":3,"ts":${T0 + 47_300},"level":"info","event":"turn.end","sid":"sess-0001","turnId":"00000000-0000-4000-8000-000000000001","outcome":"ok","durationMs":42300}"""),
        entry("""{"seq":4,"ts":${T0 + 61_000},"level":"warn","event":"session.evict","sid":"sess-0002","reason":"idle","outstanding":1}"""),
        entry("""{"seq":5,"ts":${T0 + 75_000},"level":"info","event":"turn.start","sid":"sess-0002","turnId":"00000000-0000-4000-8000-000000000002","continuation":true}"""),
        entry("""{"seq":6,"ts":${T0 + 92_500},"level":"error","event":"turn.error","sid":"sess-0002","turnId":"00000000-0000-4000-8000-000000000002","message":"engine exited with code 1"}"""),
        entry("""{"seq":7,"ts":${T0 + 120_000},"level":"warn","event":"lease.collision","sid":"sess-9f3c2a71b0","reason":"held by another node"}"""),
    )

    val stats = ServerStats(
        uptimeMs = 11_220_000, pid = 48213, protocolVersion = 128, headlessMode = "claude,codex",
        headlessPersistent = true, sessionsTotal = 7, sessionsHeadless = 6,
        runtime = ServerStats.Runtime(warm = 2, activeTurns = 1, maxConcurrentTurns = 4),
        memoryRss = 162_529_280, memoryHeapUsed = 49_283_072, clients = 2,
    )

    /**
     * The web reference's own state (docs/parity/screens/log-dialog/capture-web.mjs: the S0.4 fake
     * server, the idle-session page): its stats, and the connection records each viewport's log
     * held, oldest first, all at the frozen browser clock 07:02:00 UTC.
     */
    val webStats = ServerStats(
        uptimeMs = 120_000, pid = 2_888_656, protocolVersion = 128, headlessMode = "fake",
        headlessPersistent = false, sessionsTotal = 11, sessionsHeadless = 11,
        runtime = ServerStats.Runtime(warm = 0, activeTurns = 1, maxConcurrentTurns = 0),
        memoryRss = 1_167L * 1024 * 1024, memoryHeapUsed = 571L * 1024 * 1024, clients = 5,
    )

    private const val WEB_TS = T0 + 7 * 3_600_000L + 2 * 60_000L

    private fun webLog(vararg events: String): List<LogEntry> = events.mapIndexed { i, event ->
        entry("""{"seq":${i + 1},"ts":$WEB_TS,"level":"info","event":"$event","connId":${i + 1}}""")
    }

    /** Newest first on screen: attach, connect, attach, attach, connect, connect. */
    val webPhone: List<LogEntry> = webLog("ws.connect", "ws.connect", "ws.attach", "ws.attach", "ws.connect", "ws.attach")

    /** Newest first on screen: attach, connect, attach. */
    val webTablet: List<LogEntry> = webLog("ws.attach", "ws.connect", "ws.attach")

    /**
     * The crash fixture (W30; synthetic: a fixed epoch, no hostnames, no device paths, no real
     * messages): 42 stack lines in two chunks, 12 `... more` common frames, and two exits.
     */
    val crash: CrashRecord = run {
        val message = "Synthetic failure while restoring the session list after resume"
        val frames = (1..24).map { "\tat com.tether.app.fixture.SyntheticFrames.step%02d(SyntheticFrames.kt:%d)".format(it, 100 + it) }
        val cause = (1..15).map { "\tat com.tether.app.fixture.SyntheticCause.step%02d(SyntheticCause.kt:%d)".format(it, 200 + it) }
        val stack = (listOf("java.lang.IllegalStateException: $message") + frames +
            listOf("Caused by: java.lang.IllegalArgumentException: Synthetic root cause") + cause + listOf("\t... 12 more"))
            .joinToString("\n")
        CrashRecord(
            timeMs = T0 - 3_595_750, versionName = "0.6.0", versionCode = 16, androidRelease = "16", androidSdk = 36,
            thread = "main", exceptionClass = "java.lang.IllegalStateException", message = message, stack = stack,
        )
    }

    /** Newest first: a Java crash 230 ms after the record, and a low-memory kill the day before. */
    val exits: List<ProcessExit> = listOf(
        ProcessExit(timeMs = crash.timeMs + 230, reason = 4, importance = 100, status = 0, pid = 21734, description = "crash"),
        ProcessExit(timeMs = T0 - 105_463_000, reason = 3, importance = 400, status = 0, pid = 20211, description = null),
    )

    private fun session(id: String, name: String) =
        AgentSession(id = id, provider = "claude", name = name, cwd = "/work/app", status = "ready", startedAt = 1, updatedAt = 1)
}
