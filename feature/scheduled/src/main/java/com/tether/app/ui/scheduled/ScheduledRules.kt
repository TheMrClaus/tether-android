package com.tether.app.ui.scheduled

import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledAction
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.protocol.helpers.DeepseekPeak
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** scheduled-actions-view.tsx `CadenceMode`. */
enum class CadenceMode(val key: String) { Preset("preset"), Custom("custom"), Once("once") }

/** scheduled-actions-view.tsx `CADENCE_PRESETS`. */
data class CadencePreset(val value: String, val label: String)

/**
 * scheduled-actions-view.tsx `FormState` (`ScheduledActionInput & { providerKey }`): the editor's
 * fields. [maxRunsText] is the Run limit input's text (the web's `<input type="number">`), and
 * [extra] the edited schedule's fields this client does not model, sent back unchanged.
 */
data class ScheduleForm(
    val name: String = "",
    val prompt: String = "",
    val cwd: String = "",
    val providerKey: String = "",
    val provider: String = "claude",
    val profileId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val permissionMode: String? = null,
    val sandboxPolicy: String? = null,
    val useWorktree: Boolean = false,
    val cron: String = ScheduledRules.DEFAULT_CRON,
    val timeZone: String = "UTC",
    val maxRuns: Int? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** What a submit produced: the frame's input, or the form error the web shows. */
sealed interface SubmitResult {
    data class Ok(val input: ScheduledActionInput) : SubmitResult
    data class Error(val message: String) : SubmitResult
}

/**
 * components/scheduled-actions-view.tsx, its pure parts, line for line: the cadence presets and
 * the one-off encoding (a cron pinned to one minute + `maxRuns: 1`), the editor's defaults and
 * submit validation, and the row's labels. Clocks and zones are parameters (the web reads
 * `Date.now()` and the browser's zone), so the tests pin them.
 */
object ScheduledRules {
    /** scheduled-actions-view.tsx:36. */
    const val DELETE_ARM_MS = 4_000L

    /** scheduled-actions-view.tsx:143 `emptyForm` cron. */
    const val DEFAULT_CRON = "0 9 * * 1-5"

    val CADENCE_PRESETS: List<CadencePreset> = listOf(
        CadencePreset("*/5 * * * *", "Every 5 minutes"),
        CadencePreset("*/15 * * * *", "Every 15 minutes"),
        CadencePreset("0 * * * *", "Hourly"),
        CadencePreset("0 9 * * *", "Daily at 09:00"),
        CadencePreset("0 9 * * 1-5", "Weekdays at 09:00"),
    )

    private val WHITESPACE = Regex("\\s+")
    private val DIGITS = Regex("^\\d+$")
    private val ONCE_VALUE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$")

    /** JS `cron.trim().split(/\s+/)`. */
    private fun fields(cron: String): List<String> = cron.trim().split(WHITESPACE)

    /** scheduled-actions-view.tsx:58-63. */
    fun isOneOffCron(cron: String): Boolean {
        val f = fields(cron)
        if (f.size != 5) return false
        return f[4] == "*" && f.take(4).all { DIGITS.matches(it) }
    }

    /** scheduled-actions-view.tsx:65-67. */
    fun isOneOffSchedule(cron: String, maxRuns: Int?): Boolean = maxRuns == 1 && isOneOffCron(cron)

    /** scheduled-actions-view.tsx:69-73. */
    fun cadenceModeFor(cron: String, maxRuns: Int?): CadenceMode = when {
        isOneOffSchedule(cron, maxRuns) -> CadenceMode.Once
        CADENCE_PRESETS.any { it.value == cron } -> CadenceMode.Preset
        else -> CadenceMode.Custom
    }

    private fun pad2(value: Int) = value.toString().padStart(2, '0')

    /** scheduled-actions-view.tsx:79-81 (`YYYY-MM-DDTHH:mm`, the datetime-local value). */
    fun formatOnceInputValue(date: LocalDateTime): String =
        "${date.year}-${pad2(date.monthValue)}-${pad2(date.dayOfMonth)}T${pad2(date.hour)}:${pad2(date.minute)}"

    /** scheduled-actions-view.tsx:83-87: tomorrow at 09:00, local time. */
    fun defaultOnceInputValue(now: Long, zone: ZoneId): String =
        formatOnceInputValue(Instant.ofEpochMilli(now + 24 * 60 * 60_000L).atZone(zone).toLocalDate().atTime(9, 0))

    /** JS `new Date(y, m - 1, d, h, min)` / `setMonth` + `setHours`: out-of-range parts roll over. */
    private fun rolled(year: Int, month: Long, day: Long, hour: Long, minute: Long): LocalDateTime =
        LocalDateTime.of(year, 1, 1, 0, 0).plusMonths(month - 1).plusDays(day - 1).plusHours(hour).plusMinutes(minute)

    /**
     * scheduled-actions-view.tsx:93-114: the live `nextRunAt` in the schedule's own zone (it carries
     * the year), else the cron's fields in the current year, local time. An unknown zone (where
     * the web's `Intl.DateTimeFormat` would throw) falls back to the cron's fields.
     */
    fun onceInputValueFromSchedule(schedule: ScheduledAction, now: Long, zone: ZoneId): String {
        schedule.nextRunAt?.let { next ->
            val scheduleZone = try { ZoneId.of(schedule.timeZone) } catch (_: DateTimeException) { null }
            if (scheduleZone != null) return formatOnceInputValue(LocalDateTime.ofInstant(Instant.ofEpochMilli(next), scheduleZone))
        }
        val f = fields(schedule.cron)
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        return formatOnceInputValue(rolled(year, f[3].toLong(), f[2].toLong(), f[1].toLong(), f[0].toLong()))
    }

    /** scheduled-actions-view.tsx:116-121. */
    fun cronFromOnceInputValue(value: String): String? {
        val m = ONCE_VALUE.matchEntire(value) ?: return null
        val (_, month, day, hour, minute) = m.destructured
        return "${minute.toInt()} ${hour.toInt()} ${day.toInt()} ${month.toInt()} *"
    }

    /** scheduled-actions-view.tsx:123-128: the picked instant (epoch ms), local time. */
    fun dateFromOnceInputValue(value: String, zone: ZoneId): Long? {
        val m = ONCE_VALUE.matchEntire(value) ?: return null
        val (year, month, day, hour, minute) = m.destructured
        return rolled(year.toInt(), month.toLong(), day.toLong(), hour.toLong(), minute.toLong()).atZone(zone).toInstant().toEpochMilli()
    }

    /** scheduled-actions-view.tsx:208-211: the agents a schedule can run (ready, never Gemini). */
    fun entries(catalog: List<ProviderCatalogEntry>): List<ProviderCatalogEntry> =
        catalog.filter { it.status == "ready" && it.provider != "gemini" }

    /** scheduled-actions-view.tsx:130-147. */
    fun emptyForm(entry: ProviderCatalogEntry?, cwd: String, zone: ZoneId): ScheduleForm = ScheduleForm(
        cwd = cwd,
        providerKey = entry?.key ?: "",
        provider = if (entry == null || entry.provider == "gemini") "claude" else entry.provider,
        profileId = entry?.profileId,
        model = entry?.defaultModel?.takeIf { it.isNotEmpty() },
        cron = DEFAULT_CRON,
        timeZone = zone.id,
    )

    /** scheduled-actions-view.tsx:239-258 `openEdit`. */
    fun formFor(schedule: ScheduledAction): ScheduleForm = ScheduleForm(
        name = schedule.name,
        prompt = schedule.prompt,
        cwd = schedule.cwd,
        providerKey = schedule.profileId ?: schedule.provider,
        provider = schedule.provider,
        profileId = schedule.profileId,
        model = schedule.model,
        reasoningEffort = schedule.reasoningEffort,
        permissionMode = schedule.permissionMode,
        sandboxPolicy = schedule.sandboxPolicy,
        useWorktree = schedule.useWorktree,
        cron = schedule.cron,
        timeZone = schedule.timeZone,
        maxRuns = schedule.maxRuns,
        extra = schedule.unknown,
    )

    /** scheduled-actions-view.tsx:265-278 `selectProvider`: the entry's defaults; a Gemini or unknown key changes nothing. */
    fun selectProvider(form: ScheduleForm, entries: List<ProviderCatalogEntry>, key: String): ScheduleForm {
        val entry = entries.firstOrNull { it.key == key } ?: return form
        if (entry.provider == "gemini") return form
        return form.copy(
            providerKey = key,
            provider = entry.provider,
            profileId = entry.profileId,
            model = entry.defaultModel?.takeIf { it.isNotEmpty() },
            reasoningEffort = null,
        )
    }

    /** scheduled-actions-view.tsx:560-580: the Cadence select's change ("custom", "once" or a preset's cron). */
    fun selectCadence(form: ScheduleForm, onceValue: String, next: String, now: Long, zone: ZoneId): Triple<ScheduleForm, CadenceMode, String> = when (next) {
        CadenceMode.Custom.key -> Triple(form, CadenceMode.Custom, onceValue)
        CadenceMode.Once.key -> {
            val value = onceValue.ifEmpty { defaultOnceInputValue(now, zone) }
            Triple(form.copy(timeZone = zone.id, maxRuns = 1, cron = cronFromOnceInputValue(value) ?: form.cron), CadenceMode.Once, value)
        }
        else -> Triple(form.copy(cron = next), CadenceMode.Preset, onceValue)
    }

    /** scheduled-actions-view.tsx:614-623: the Run at input's change. */
    fun pickOnce(form: ScheduleForm, value: String, zone: ZoneId): ScheduleForm =
        form.copy(timeZone = zone.id, maxRuns = 1, cron = cronFromOnceInputValue(value) ?: form.cron)

    /**
     * The browser's own check of the Run limit input before a submit (`<input type="number"
     * min={1}>` inside the `<form>`): a value under 1 never submits. Disabled (Run once) it is not
     * checked. Chrome's words.
     */
    const val RUN_LIMIT_MIN_MESSAGE = "Value must be greater than or equal to 1."

    /** scheduled-actions-view.tsx:280-319 `submit` (after the browser's constraint check). */
    fun submit(form: ScheduleForm, mode: CadenceMode, onceValue: String, now: Long, zone: ZoneId): SubmitResult {
        if (mode != CadenceMode.Once && form.maxRuns != null && form.maxRuns < 1) return SubmitResult.Error(RUN_LIMIT_MIN_MESSAGE)
        if (form.name.trim().isEmpty() || form.prompt.trim().isEmpty() || form.cwd.trim().isEmpty()) {
            return SubmitResult.Error("Name, prompt, and workspace are required.")
        }
        if (mode == CadenceMode.Once) {
            val picked = dateFromOnceInputValue(onceValue, zone) ?: return SubmitResult.Error("Pick a date and time to run once.")
            if (picked <= now) return SubmitResult.Error("Pick a time in the future.")
        }
        if (fields(form.cron).size != 5) return SubmitResult.Error("Cron cadence must contain five fields.")
        return SubmitResult.Ok(
            ScheduledActionInput(
                name = form.name.trim(),
                prompt = form.prompt.trim(),
                cwd = form.cwd.trim(),
                provider = form.provider,
                profileId = form.profileId,
                model = form.model,
                reasoningEffort = form.reasoningEffort,
                permissionMode = form.permissionMode,
                sandboxPolicy = form.sandboxPolicy,
                useWorktree = form.useWorktree,
                cron = form.cron.trim(),
                timeZone = form.timeZone.trim(),
                maxRuns = form.maxRuns,
                extra = form.extra,
            ),
        )
    }

    /** scheduled-actions-view.tsx:506: the Workspace field's suggestions (current, then pinned; unique, non-empty). */
    fun workspaceSuggestions(currentWorkspace: String, pinned: List<String>): List<String> =
        (listOf(currentWorkspace) + pinned).filter { it.isNotEmpty() }.distinct()

    /** JS `Math.round` (halves toward +∞). */
    private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

    /** `Intl.RelativeTimeFormat("en", { numeric: "auto" })` for the three units the view uses. */
    internal fun relative(value: Long, unit: String): String {
        when (unit) {
            "minute" -> if (value == 0L) return "this minute"
            "hour" -> if (value == 0L) return "this hour"
            "day" -> when (value) {
                0L -> return "today"
                1L -> return "tomorrow"
                -1L -> return "yesterday"
            }
        }
        val n = abs(value)
        val word = if (n == 1L) unit else "${unit}s"
        return if (value > 0) "in $n $word" else "$n $word ago"
    }

    /**
     * `Intl.DateTimeFormat(undefined, {dateStyle: "medium", timeStyle: "short"})` ("Oct 3, 2026,
     * 9:00 AM" in en-US); CLDR's narrow no-break space before the day period as a plain space.
     */
    fun exactTime(timestamp: Long?, zone: ZoneId, locale: Locale = Locale.getDefault()): String? {
        if (timestamp == null || timestamp == 0L) return null
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
        return formatter.format(Instant.ofEpochMilli(timestamp)).replace('\u202F', ' ').replace('\u00A0', ' ')
    }

    /** scheduled-actions-view.tsx:149-158. */
    fun timeLabel(timestamp: Long?, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        if (timestamp == null || timestamp == 0L) return "Not yet"
        val delta = (timestamp - now).toDouble()
        val absDelta = abs(delta)
        if (absDelta < 90 * 60_000) return relative(jsRound(delta / 60_000), "minute")
        if (absDelta < 36e5 * 24) return relative(jsRound(delta / 36e5), "hour")
        if (absDelta < 36e5 * 24 * 14) return relative(jsRound(delta / (36e5 * 24)), "day")
        return exactTime(timestamp, zone, locale)!!
    }

    /** scheduled-actions-view.tsx:166-171. */
    fun cadenceLabel(schedule: ScheduledAction, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        if (isOneOffSchedule(schedule.cron, schedule.maxRuns)) {
            return if (schedule.nextRunAt != null && schedule.nextRunAt != 0L) "Once · ${exactTime(schedule.nextRunAt, zone, locale)}" else "Runs once"
        }
        return CADENCE_PRESETS.firstOrNull { it.value == schedule.cron }?.label ?: schedule.cron
    }

    /**
     * scheduled-actions-view.tsx:173-179. Like the web, it reads `runs[0]`: the OLDEST run the
     * server kept (it appends), while the row's status and error read the newest.
     */
    fun runSummary(schedule: ScheduledAction, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val run = schedule.runs.firstOrNull() ?: return "Never run"
        if (run.status == "running") return "Running now"
        if (run.status == "failed") return "Last run failed · ${timeLabel(run.endedAt, now, zone, locale)}"
        return "Last ran ${timeLabel(run.endedAt, now, zone, locale)}"
    }

    /** scheduled-actions-view.tsx:403: the next column's caption. */
    fun nextCaption(schedule: ScheduledAction): String = when (schedule.status) {
        "active" -> "Next run"
        "completed" -> "Runs"
        else -> "Paused"
    }

    /** scheduled-actions-view.tsx:405: the next column's figure. */
    fun nextValue(schedule: ScheduledAction, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String = when (schedule.status) {
        "active" -> timeLabel(schedule.nextRunAt, now, zone, locale)
        "completed" -> "${schedule.runs.count { !it.manual }} completed"
        else -> "No run queued"
    }

    /** scheduled-actions-view.tsx:387-389: the pill's word ("Running" while the newest run runs, else the status). */
    fun statusWord(schedule: ScheduledAction): String =
        if (schedule.runs.lastOrNull()?.status == "running") "Running" else schedule.status

    /**
     * components/deepseek-peak.tsx `DeepSeekScheduleHint` (issue #193): an ACTIVE DeepSeek-API
     * schedule whose next run falls in DeepSeek's peak window.
     */
    fun deepSeekPeakHint(schedule: ScheduledAction): Boolean {
        if (schedule.status != "active") return false
        val next = schedule.nextRunAt ?: return false
        DeepseekPeak.deepSeekApiTier(JsStr(schedule.provider), schedule.model?.let(::JsStr)) ?: return false
        return (DeepseekPeak.deepSeekPeakAtDate(next.toDouble())?.get("isPeak") as? JsBool)?.value == true
    }

    const val DEEPSEEK_HINT = "Next run is in DeepSeek peak hours · 2× rate"
    const val DEEPSEEK_HINT_TITLE = "DeepSeek bills 2× off-peak during 01:00–04:00 and 06:00–10:00 UTC, Monday–Friday, except Chinese public holidays."
}
