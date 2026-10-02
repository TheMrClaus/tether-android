package com.tether.app.ui.usage

import androidx.compose.runtime.staticCompositionLocalOf
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.helpers.Format
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * T9.2: the clock, locale and zone the Usage surfaces read. Production reads the device's; tests
 * and goldens provide a fixed one ([LocalUsageEnv]) so every countdown and timestamp is stable.
 */
data class UsageEnv(
    val now: () -> Long = System::currentTimeMillis,
    val locale: Locale = Locale.getDefault(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** A fixed DeepSeek clock instant (goldens); null = the shared 30 s ticker. */
    val fixedNow: Long? = null,
)

val LocalUsageEnv = staticCompositionLocalOf { UsageEnv() }

/**
 * The web's number and date formats on these surfaces (usage-dashboard.tsx:52-58, the dialogs'
 * `toLocale*` calls), rendered in their en-US shape.
 */
object UsageFormat {
    /** `compact.format(n || 0)`: Intl "en" compact, one fraction digit. */
    fun compact(n: Double): String = Format.compactNumber(if (n.isFinite()) n else 0.0)

    /** `whole.format(n || 0)`: Intl "en" grouping, at most three fraction digits. */
    fun whole(n: Double): String {
        val v = if (n.isFinite()) n else 0.0
        val rounded = BigDecimal(numberToString(kotlin.math.abs(v))).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        return sign(v) + group(rounded)
    }

    /** `money.format(n)`: USD, no fraction digits ("$1,235"). */
    fun money(n: Double): String = sign(n) + "$" + group(BigDecimal(numberToString(kotlin.math.abs(safe(n)))).setScale(0, RoundingMode.HALF_UP))

    /** `money2.format(n)`: USD, two fraction digits ("$12.35"). */
    fun money2(n: Double): String = sign(n) + "$" + group(BigDecimal(numberToString(kotlin.math.abs(safe(n)))).setScale(2, RoundingMode.HALF_UP))

    /** A JS number's own text (`${pct}%`, `${sharePct}%`). */
    fun js(n: Double): String = numberToString(n)

    /** `toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })` → "09:05 AM". */
    fun clock(ms: Double, env: UsageEnv): String = pattern("hh:mm a", ms, env)

    /** `toLocaleTimeString()` → "9:05:12 AM". */
    fun longClock(ms: Double, env: UsageEnv): String = pattern("h:mm:ss a", ms, env)

    /** `toLocaleString()` → "10/3/2026, 9:05:12 AM". */
    fun dateTime(ms: Double, env: UsageEnv): String = pattern("M/d/yyyy, h:mm:ss a", ms, env)

    /** `toLocaleDateString(undefined, { month: "short", day: "numeric" })` → "Sep 24". */
    fun monthDay(ms: Double, env: UsageEnv): String = pattern("MMM d", ms, env)

    /** claude-reset-grant-dialog.tsx `when()`: "Thu, Sep 24, 9:05 AM". */
    fun weekdayDateTime(ms: Double, env: UsageEnv): String = pattern("EEE, MMM d, h:mm a", ms, env)

    private fun pattern(p: String, ms: Double, env: UsageEnv): String =
        if (!ms.isFinite()) "Invalid Date" else DateTimeFormatter.ofPattern(p, env.locale).format(Instant.ofEpochMilli(ms.toLong()).atZone(env.zone))

    private fun safe(n: Double) = if (n.isFinite()) n else 0.0

    private fun sign(n: Double) = if (n < 0) "-" else ""

    private fun group(value: BigDecimal): String {
        val plain = value.toPlainString()
        val integer = plain.substringBefore('.')
        val grouped = integer.reversed().chunked(3).joinToString(",").reversed()
        return grouped + plain.substring(integer.length)
    }

    /** usage-dashboard.tsx `shortPath`: "—", the path itself, or "…/<last two>". */
    fun shortPath(p: String?): String {
        if (p.isNullOrEmpty()) return "—"
        val parts = p.split("/").filter { it.isNotEmpty() }
        return if (parts.size <= 2) p else "…/${parts.takeLast(2).joinToString("/")}"
    }
}
