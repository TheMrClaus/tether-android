package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.protocol.ServerMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.flow.MutableStateFlow

/** lib/protocol.ts `ScheduledActionRun` (v87). */
data class ScheduledActionRun(
    val id: String,
    /** "running" | "succeeded" | "failed" (an unknown value is kept as sent). */
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val sessionId: String?,
    val error: String?,
    val manual: Boolean,
)

/**
 * lib/protocol.ts `ScheduledAction` (v87): a fresh-agent cron schedule. [unknown] keeps every field
 * this client does not model (a newer server's), so an edit can send it back unchanged ([toInput]);
 * v143's `setupConsent` is modelled ([setupConsent]).
 */
data class ScheduledAction(
    val id: String,
    val name: String,
    val prompt: String,
    val cwd: String,
    val provider: String,
    val profileId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val permissionMode: String?,
    val sandboxPolicy: String?,
    val useWorktree: Boolean,
    val cron: String,
    val timeZone: String,
    val maxRuns: Int?,
    /** "active" | "paused" | "completed". */
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    /** Oldest first, as the server appends them (lib/scheduled-actions.mjs `runs`). */
    val runs: List<ScheduledActionRun>,
    val unknown: JsonObject = JsonObject(emptyMap()),
    /**
     * v143 r3 (ta-6t1): the setup approval saved with an isolated schedule, as the server stored it (a string, or
     * an explicit null). Null: not sent (an older server). An edit's starting input carries it unchanged.
     */
    val setupConsent: com.tether.app.protocol.OrNull<String>? = null,
) {
    /**
     * The schedule as an edit's starting input: its own fields plus [unknown] (written back
     * unchanged; the server's validator keeps only what it knows).
     */
    fun toInput(): ScheduledActionInput = ScheduledActionInput(
        name = name, prompt = prompt, cwd = cwd, provider = provider, profileId = profileId, model = model,
        reasoningEffort = reasoningEffort, permissionMode = permissionMode, sandboxPolicy = sandboxPolicy,
        useWorktree = useWorktree, cron = cron, timeZone = timeZone, maxRuns = maxRuns,
        // v143: the stored approval is modelled ([ScheduledActionInput.setupConsent]); a stored null stays an explicit null.
        setupConsent = setupConsent,
        extra = unknown,
    )

    companion object {
        /** Every key this client reads from a schedule; anything else is [unknown]. */
        val KEYS: Set<String> = ScheduledActionInput.KEYS + setOf("id", "status", "createdAt", "updatedAt", "nextRunAt", "lastRunAt", "runs")
    }
}

/** lib/protocol.ts `ScheduledContinuation`: a session waiting to resume after its usage limit resets. */
data class ScheduledContinuation(
    val sessionId: String,
    val sessionName: String,
    val provider: String,
    val cwd: String,
    val resetsAt: Long,
    val resumeAt: Long,
)

/**
 * use-tether.ts `scheduledActions` / `scheduledContinuations`: the last `scheduled-actions` frame
 * ([loaded] false until one arrived from this server).
 */
data class ScheduledActionsState(
    val schedules: List<ScheduledAction> = emptyList(),
    val continuations: List<ScheduledContinuation> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Tolerant decoding of the `scheduled-actions` frame: an entry missing a required field (or with
 * one of the wrong type) is dropped, never fatal; a nullable field of the wrong type reads null.
 */
object ScheduledActionsParse {
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.long(key: String): Long? = num(key)?.takeIf { it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() }?.toLong()
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

    fun run(o: JsonObject): ScheduledActionRun? = ScheduledActionRun(
        id = o.str("id") ?: return null,
        status = o.str("status") ?: return null,
        startedAt = o.long("startedAt") ?: return null,
        endedAt = o.long("endedAt"),
        sessionId = o.str("sessionId"),
        error = o.str("error"),
        manual = o.bool("manual") ?: false,
    )

    fun schedule(o: JsonObject): ScheduledAction? = ScheduledAction(
        id = o.str("id") ?: return null,
        name = o.str("name") ?: return null,
        prompt = o.str("prompt") ?: return null,
        cwd = o.str("cwd") ?: return null,
        provider = o.str("provider") ?: return null,
        profileId = o.str("profileId"),
        model = o.str("model"),
        reasoningEffort = o.str("reasoningEffort"),
        permissionMode = o.str("permissionMode"),
        sandboxPolicy = o.str("sandboxPolicy"),
        useWorktree = o.bool("useWorktree") ?: false,
        cron = o.str("cron") ?: return null,
        timeZone = o.str("timeZone") ?: return null,
        maxRuns = o.num("maxRuns")?.takeIf { it >= 1 && it <= Int.MAX_VALUE }?.toInt(),
        status = o.str("status") ?: return null,
        createdAt = o.long("createdAt") ?: 0L,
        updatedAt = o.long("updatedAt") ?: 0L,
        nextRunAt = o.long("nextRunAt"),
        lastRunAt = o.long("lastRunAt"),
        runs = (o["runs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::run) },
        unknown = JsonObject(o.filterKeys { it !in ScheduledAction.KEYS }),
        setupConsent = when (val consent = o["setupConsent"]) {
            null -> null
            is kotlinx.serialization.json.JsonNull -> com.tether.app.protocol.OrNull(null)
            is JsonPrimitive -> if (consent.isString) com.tether.app.protocol.OrNull(consent.content) else null
            else -> null
        },
    )

    fun continuation(o: JsonObject): ScheduledContinuation? = ScheduledContinuation(
        sessionId = o.str("sessionId") ?: return null,
        sessionName = o.str("sessionName") ?: "",
        provider = o.str("provider") ?: "",
        cwd = o.str("cwd") ?: "",
        resetsAt = o.long("resetsAt") ?: return null,
        resumeAt = o.long("resumeAt") ?: return null,
    )

    fun state(frame: ServerMessage.ScheduledActions): ScheduledActionsState = ScheduledActionsState(
        schedules = frame.schedules.mapNotNull(::schedule),
        continuations = frame.continuations.mapNotNull(::continuation),
        loaded = true,
    )
}

/**
 * T9.3: the Scheduled destination's client state, apart from the connection code (like
 * [SearchSync]). Mirrors use-tether.ts: the snapshot asked for on every new socket (798-801), the
 * frame replacing both lists (885-887), and the four sends (1915-1923) plus the continuation's
 * cancel (`rate-limit-resume` `dismiss`, dashboard.tsx:1598). The sends are fire-and-forget like
 * the web's: the server answers with a fresh `scheduled-actions` broadcast, or an `error`.
 */
internal class ScheduledActionsSync(private val send: (ClientMessage) -> Boolean) {
    val state = MutableStateFlow(ScheduledActionsState())

    fun request(): Boolean = send(ClientMessage.ScheduledActionsRequest)

    fun create(schedule: ScheduledActionInput): Boolean = send(ClientMessage.ScheduleCreate(schedule))

    fun update(scheduleId: String, schedule: ScheduledActionInput): Boolean = send(ClientMessage.ScheduleUpdate(scheduleId, schedule))

    fun control(scheduleId: String, action: String): Boolean = send(ClientMessage.ScheduleControl(scheduleId, action))

    fun cancelContinuation(sessionId: String, resetsAt: Long): Boolean = send(ClientMessage.RateLimitResume(sessionId, resetsAt, "dismiss"))

    /** Folds the frame; false for a frame this class does not own. */
    fun onFrame(message: ServerMessage): Boolean {
        if (message !is ServerMessage.ScheduledActions) return false
        state.value = ScheduledActionsParse.state(message)
        return true
    }

    /** Another server's schedules must never show. */
    fun clear() {
        state.value = ScheduledActionsState()
    }
}
