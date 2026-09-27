package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.jsToString
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.js
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.floor

/**
 * T2.2: faithful port of lib/deepseek-peak.mjs — DeepSeek API peak / off-peak billing (issue #193).
 * Strictly pure: the instant is an argument. The windows are defined in UTC, so the reading is
 * timezone-independent; only the viewer's rendering of it is not.
 */
object DeepseekPeak {

    // lib/deepseek-peak.mjs:30 — [startHourUtc, endHourUtc) on a UTC weekday.
    val PEAK_WINDOWS_UTC: List<Pair<Int, Int>> = listOf(1 to 4, 6 to 10)

    data class HolidayBreak(val name: String, val zh: String, val dates: List<String>)

    // lib/deepseek-peak.mjs:67 — 国办发明电〔2025〕7号, every date written out.
    val DEEPSEEK_HOLIDAY_BREAKS: List<HolidayBreak> = listOf(
        HolidayBreak("New Year's Day", "元旦", listOf("2026-01-01", "2026-01-02", "2026-01-03")),
        HolidayBreak(
            "Spring Festival",
            "春节",
            listOf(
                "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
                "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
            ),
        ),
        HolidayBreak("Qingming Festival", "清明节", listOf("2026-04-04", "2026-04-05", "2026-04-06")),
        HolidayBreak("Labour Day", "劳动节", listOf("2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05")),
        HolidayBreak("Dragon Boat Festival", "端午节", listOf("2026-06-19", "2026-06-20", "2026-06-21")),
        HolidayBreak("Mid-Autumn Festival", "中秋节", listOf("2026-09-25", "2026-09-26", "2026-09-27")),
        HolidayBreak(
            "National Day",
            "国庆节",
            listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"),
        ),
    )

    // lib/deepseek-peak.mjs:95
    val DEEPSEEK_HOLIDAYS: List<String> = DEEPSEEK_HOLIDAY_BREAKS.flatMap { it.dates }

    // lib/deepseek-peak.mjs:104 — derived from the dates themselves.
    val DEEPSEEK_HOLIDAY_YEARS: List<Int> = DEEPSEEK_HOLIDAYS.map { it.substring(0, 4).toInt() }.distinct()
    private val HOLIDAY_YEAR_SET = DEEPSEEK_HOLIDAY_YEARS.toSet()

    private val HOLIDAY_BY_DATE: Map<String, HolidayBreak> = DEEPSEEK_HOLIDAY_BREAKS.flatMap { entry -> entry.dates.map { it to entry } }.toMap()
    private const val DAY_MS = 24.0 * 60 * 60 * 1000
    private const val NEXT_PEAK_SEARCH_DAYS = 31

    private fun card(model: String, effectiveFrom: String, offPeak: Triple<Double, Double, Double>, peak: Triple<Double, Double, Double>) = JsObj.of(
        "model" to JsStr(model),
        "label" to JsStr(model),
        "effectiveFrom" to js(Instant.parse(effectiveFrom).toEpochMilli()),
        "offPeak" to JsObj.of("cacheHit" to js(offPeak.first), "cacheMiss" to js(offPeak.second), "output" to js(offPeak.third)),
        "peak" to JsObj.of("cacheHit" to js(peak.first), "cacheMiss" to js(peak.second), "output" to js(peak.third)),
    )

    // lib/deepseek-peak.mjs:160 — USD per 1M tokens (raw docs page, 2026-09-25).
    val DEEPSEEK_RATES: JsObj = JsObj.of(
        "flash" to card("deepseek-flash", "2026-09-10T04:00:00Z", Triple(0.003, 0.15, 0.6), Triple(0.006, 0.3, 1.2)),
        "pro" to card("deepseek-v4-pro", "2026-08-22T16:00:00Z", Triple(0.022, 0.66, 1.98), Triple(0.044, 1.32, 3.96)),
    )

    // lib/deepseek-peak.mjs:178
    val DEEPSEEK_RATE_ROWS: JsArr = JsArr.of(
        JsObj.of("key" to JsStr("cacheHit"), "label" to JsStr("1M input tokens (cache hit)")),
        JsObj.of("key" to JsStr("cacheMiss"), "label" to JsStr("1M input tokens (cache miss)")),
        JsObj.of("key" to JsStr("output"), "label" to JsStr("1M output tokens")),
    )

    private val FLASH = Regex("^deepseek-(?:v4(?:\\.1)?-)?flash(?![a-z0-9])")
    private val PRO = Regex("^deepseek-(?:v4-)?pro(?![a-z0-9])")

    // lib/deepseek-peak.mjs:203 — "flash" | "pro" | null: the ONE alias map for DeepSeek ids.
    fun deepSeekModelTier(model: JsValue?): String? {
        if (model !is JsStr || model.value.isEmpty()) return null
        val id = com.tether.app.protocol.fold.jsTrim(model.value).lowercase().split("/").last()
        if (id.contains(':')) return null
        if (FLASH.containsMatchIn(id)) return "flash"
        if (PRO.containsMatchIn(id)) return "pro"
        return null
    }

    fun deepSeekModelTier(model: String?): String? = deepSeekModelTier(model?.let { JsStr(it) })

    // lib/deepseek-peak.mjs:219
    val DEEPSEEK_API_PROVIDERS: List<String> = listOf("dsh", "pi", "opencode", "reasonix")

    // lib/deepseek-peak.mjs:227
    const val DSH_DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"

    private const val DEEPSEEK_PROVIDER_NAMESPACE = "deepseek"

    // lib/deepseek-peak.mjs:249 — THE predicate: the card a turn is billed at by the DeepSeek API, or null.
    fun deepSeekApiTier(provider: JsValue?, model: JsValue?): String? {
        val p = (provider as? JsStr)?.value
        if (p == null || p !in DEEPSEEK_API_PROVIDERS) return null
        val id = if (model is JsStr) com.tether.app.protocol.fold.jsTrim(model.value) else ""
        if (id.isEmpty()) return if (p == "dsh") deepSeekModelTier(DSH_DEFAULT_DEEPSEEK_MODEL) else null
        val slash = id.indexOf('/')
        if (slash >= 0) {
            if (id.substring(0, slash).lowercase() != DEEPSEEK_PROVIDER_NAMESPACE) return null
            val rest = id.substring(slash + 1)
            if (rest.contains('/')) return null
            return deepSeekModelTier(rest)
        }
        if (p == "opencode") return null
        return deepSeekModelTier(id)
    }

    // lib/deepseek-peak.mjs:276 — the pick, else the harness-applied default (never for pi).
    fun deepSeekLiveModel(provider: JsValue?, selectedModel: JsValue?, appliedDefaultModel: JsValue?): String? {
        selectedModel.nonEmptyStr?.let { return it }
        if (provider.isStr("pi")) return null
        return appliedDefaultModel.nonEmptyStr
    }

    // lib/deepseek-peak.mjs:283 — `model` defaults (when undefined) to `session.model`.
    fun isDeepSeekApiSession(session: JsValue?, model: JsValue? = null): Boolean {
        if (!com.tether.app.protocol.fold.truthy(session)) return false
        return deepSeekApiTier(session["provider"], model ?: session["model"]) != null
    }

    private fun utc(ms: Double) = Instant.ofEpochMilli(ms.toLong()).atZone(ZoneOffset.UTC)

    // lib/deepseek-peak.mjs:299 — `toISOString().slice(0, 10)`.
    private fun utcDayKey(ms: Double): String = utc(ms).toLocalDate().toString()

    private fun holidayOn(ms: Double): HolidayBreak? = HOLIDAY_BY_DATE[utcDayKey(ms)]
    private fun isHoliday(ms: Double) = holidayOn(ms) != null
    private fun holidayCalendarCovers(ms: Double) = utc(ms).year in HOLIDAY_YEAR_SET
    private fun isWeekendUtc(ms: Double) = utc(ms).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }
    private fun isPeakDay(ms: Double) = !isWeekendUtc(ms) && !isHoliday(ms)

    /** `Date.UTC(y, m, d)` of the instant's UTC day, in ms. */
    private fun utcDayStart(ms: Double): Double = utc(ms).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toDouble()

    // lib/deepseek-peak.mjs:291 — a finite number, or a valid Date; null otherwise.
    private fun toMs(at: JsValue?): Double? = (at as? JsNum)?.value?.takeIf { it.isFinite() }

    /** `new Date(ms)` is only valid within ±8.64e15; the web's getters read NaN beyond it. */
    private fun usable(ms: Double) = kotlin.math.abs(ms) <= 8.64e15

    // lib/deepseek-peak.mjs:367 — the peak/off-peak reading for an instant (a number here; see [deepSeekPeakAtDate]).
    fun deepSeekPeakAt(at: JsValue?): JsObj? = deepSeekPeakAtMs(toMs(at))

    /** `deepSeekPeakAt(date)` for a JS Date: [epochMs] is its getTime(), NaN for an Invalid Date. */
    fun deepSeekPeakAtDate(epochMs: Double): JsObj? = deepSeekPeakAtMs(epochMs.takeIf { it.isFinite() })

    private fun deepSeekPeakAtMs(msOrNull: Double?): JsObj? {
        val ms = msOrNull?.takeIf { usable(it) } ?: return null

        val reasonBase = if (isHoliday(ms)) "holiday" else if (isWeekendUtc(ms)) "weekend" else "outside-window"
        var isPeak = false
        var windowEndsAt: Double? = null
        if (isPeakDay(ms)) {
            val dayStart = utcDayStart(ms)
            for ((start, end) in PEAK_WINDOWS_UTC) {
                val s = dayStart + start * 3_600_000.0
                val e = dayStart + end * 3_600_000.0
                if (ms >= s && ms < e) {
                    isPeak = true
                    windowEndsAt = e
                    break
                }
            }
        }

        // Next peak start: walk candidate UTC days from today forward.
        var nextPeakAt: Double? = null
        val today = utcDayStart(ms)
        var offset = 0
        while (offset <= NEXT_PEAK_SEARCH_DAYS && nextPeakAt == null) {
            val dayStart = today + offset * DAY_MS
            offset += 1
            if (!isPeakDay(dayStart)) continue
            for ((start, _) in PEAK_WINDOWS_UTC) {
                val candidate = dayStart + start * 60 * 60 * 1000
                if (candidate > ms) {
                    nextPeakAt = candidate
                    break
                }
            }
        }

        val holiday = holidayOn(ms)
        return JsObj.of(
            "ms" to js(ms),
            "isPeak" to js(isPeak),
            "rate" to JsStr(if (isPeak) "peak" else "off-peak"),
            "reason" to JsStr(if (isPeak) "window" else reasonBase),
            "windowEndsAt" to (windowEndsAt?.let { js(it) } ?: JsNull),
            "nextPeakAt" to (nextPeakAt?.let { js(it) } ?: JsNull),
            "holidayCalendarCovered" to js(holidayCalendarCovers(ms)),
            "holiday" to (holiday?.let { JsObj.of("name" to JsStr(it.name), "zh" to JsStr(it.zh)) } ?: JsNull),
        )
    }

    // lib/deepseek-peak.mjs:423 — "2d 12h 4m", "1h 3m", "4m", "<1m"; "" for a non-positive duration.
    fun deepSeekCountdown(ms: JsValue?): String {
        val d = (ms as? JsNum)?.value
        if (d == null || !d.isFinite() || d <= 0) return ""
        val totalMinutes = floor(d / 60_000)
        if (totalMinutes < 1) return "<1m"
        val days = floor(totalMinutes / (60 * 24))
        val hours = floor((totalMinutes % (60 * 24)) / 60)
        val minutes = totalMinutes % 60
        val n = { d: Double -> com.tether.app.protocol.fold.numberToString(d) }
        if (days > 0) return "${n(days)}d ${n(hours)}h ${n(minutes)}m"
        if (hours > 0) return "${n(hours)}h ${n(minutes)}m"
        return "${n(minutes)}m"
    }

    // lib/deepseek-peak.mjs:442
    const val DEEPSEEK_PEAK_IMMINENT_MS = 2 * 60 * 60 * 1000

    // lib/deepseek-peak.mjs:466 — the words every surface prints for an instant.
    fun deepSeekPeakCopy(at: JsValue?): JsObj? = copyOf(deepSeekPeakAt(at))

    fun deepSeekPeakCopyDate(epochMs: Double): JsObj? = copyOf(deepSeekPeakAtDate(epochMs))

    private fun copyOf(reading: JsObj?): JsObj? {
        if (reading == null) return null
        val isPeak = reading["isPeak"] == JsBool.TRUE
        val ms = (reading["ms"] as JsNum).value
        val boundaryAt = if (isPeak) reading["windowEndsAt"] else reading["nextPeakAt"]
        val remaining = (boundaryAt as? JsNum)?.let { it.value - ms }
        val countdown = if (remaining == null) "" else deepSeekCountdown(JsNum(remaining))
        val label = if (isPeak) "Peak" else "Off-peak"
        val detail = if (isPeak) "2× rate" else "50% cheaper"
        val next = if (countdown.isNotEmpty()) "${if (isPeak) "Off-peak" else "Peak"} in $countdown" else ""
        val imminent = isPeak || (remaining != null && remaining <= DEEPSEEK_PEAK_IMMINENT_MS)
        val reason = (reading["reason"] as JsStr).value
        val holiday = reading["holiday"] as? JsObj
        val why = when {
            reason == "holiday" && holiday != null ->
                "${jsToString(holiday["name"])} (${jsToString(holiday["zh"])}) — Chinese public holiday, off-peak all day"
            reason == "weekend" -> "Weekend — off-peak all day"
            else -> ""
        }
        return JsObj.of(
            "state" to reading["rate"],
            "reason" to reading["reason"],
            "label" to JsStr(label),
            "detail" to JsStr(detail),
            "next" to JsStr(next),
            "why" to JsStr(why),
            "holidayName" to JsStr(if (reason == "holiday" && holiday != null) jsToString(holiday["name"]) else ""),
            "boundaryAt" to (boundaryAt ?: JsNull),
            "imminent" to js(imminent),
            "holidayCalendarMissing" to js(reading["holidayCalendarCovered"] != JsBool.TRUE),
            "compact" to jsStrings(listOf(label, if (imminent && next.isNotEmpty()) next else detail)),
            "full" to jsStrings(listOf(label, detail, next).filter { it.isNotEmpty() }),
        )
    }

    // lib/deepseek-peak.mjs:500 — the published card for a tier, or null.
    fun deepSeekRatesForTier(tier: JsValue?): JsObj? = DEEPSEEK_RATES[jsToString(tier)] as? JsObj

    fun deepSeekRatesForTier(tier: String?): JsObj? = if (tier == null) null else DEEPSEEK_RATES[tier] as? JsObj

    // lib/deepseek-peak.mjs:505
    fun deepSeekRatesForModel(model: JsValue?): JsObj? = deepSeekRatesForTier(deepSeekModelTier(model) ?: return null)

    // lib/deepseek-peak.mjs:517 — null for an unpriced model; false for an estimate.
    fun deepSeekRateVerified(model: JsValue?, at: JsValue?): Boolean? {
        val rates = deepSeekRatesForModel(model) ?: return null
        val reading = deepSeekPeakAt(at) ?: return false
        return reading["holidayCalendarCovered"] == JsBool.TRUE &&
            (reading["ms"] as JsNum).value >= (rates["effectiveFrom"] as JsNum).value
    }

    // lib/deepseek-peak.mjs:525 — `{ tier, ...reading, amounts }` in force at an instant.
    fun deepSeekRateInForce(model: JsValue?, at: JsValue?): JsObj? {
        val rates = deepSeekRatesForModel(model) ?: return null
        val peak = deepSeekPeakAt(at) ?: return null
        return JsObj.of("tier" to jsStrOrNull(deepSeekModelTier(model)))
            .spread(peak)
            .put("amounts", if (peak["isPeak"] == JsBool.TRUE) rates["peak"] else rates["offPeak"])
    }
}
