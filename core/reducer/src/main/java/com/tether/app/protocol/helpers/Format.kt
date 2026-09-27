package com.tether.app.protocol.helpers

import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * T2.2: faithful port of lib/format.ts (tether @ PARITY_BASE). Pure; the clock is a parameter.
 *
 * The web formats three branches with the HOST locale (`toLocale*String(undefined, …)`), which
 * the conformance corpus deliberately does not record: [relativeTime] ≥ 7 days, [absoluteTime]
 * and [clockTime]'s finite branch. Here they take a [Locale] and [ZoneId] and render the en-US
 * shape of the web's option bag (unit-tested against java.time, corpus-uncovered).
 */
object Format {

    // lib/format.ts:3
    val statusCopy: Map<String, String> = linkedMapOf(
        "ready" to "Ready",
        "active" to "Active",
        "waiting" to "Needs you",
        "exited" to "Ended",
    )

    // lib/format.ts:10
    fun compactPath(value: String, root: String): String {
        if (value.isEmpty()) return "—"
        // `value.replace(root, "~")` with a string pattern replaces the FIRST occurrence only.
        return if (root.isNotEmpty() && value.startsWith(root)) value.replaceFirst(root, "~") else value
    }

    // lib/format.ts:15
    fun projectName(value: String): String = value.split("/").lastOrNull { it.isNotEmpty() } ?: "Root"

    // lib/format.ts:19 — the ≥ 7-day branch is host-locale formatted on the web (corpus-uncovered).
    fun relativeTime(
        timestamp: Double,
        nowMs: Double = System.currentTimeMillis().toDouble(),
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val seconds = max(0.0, floor((nowMs - timestamp) / 1000))
        if (seconds < 60) return "now"
        val minutes = floor(seconds / 60)
        if (minutes < 60) return "${numberToString(minutes)}m"
        val hours = floor(minutes / 60)
        if (hours < 24) return "${numberToString(hours)}h"
        val days = floor(hours / 24)
        if (days < 7) return "${numberToString(days)}d"
        // toLocaleDateString(undefined, { month: "short", day: "numeric" }) → en-US "Sep 24".
        return DateTimeFormatter.ofPattern("MMM d", locale).format(instant(timestamp, zone))
    }

    // lib/format.ts:37 — host-locale formatted on the web (corpus-uncovered).
    // toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "2-digit",
    // minute: "2-digit" }) → en-US "Sep 24, 2026, 09:05 AM".
    fun absoluteTime(timestamp: Double, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", locale).format(instant(timestamp, zone))

    // lib/format.ts:43 — Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).
    fun compactNumber(value: JsValue?): String {
        val d = (value as? JsNum)?.value
        if (d == null || !d.isFinite()) return "—"
        return compactEn(d)
    }

    fun compactNumber(value: Double): String = compactNumber(JsNum(value))

    // lib/format.ts:48
    fun resetTime(timestamp: JsValue?, nowMs: Double = System.currentTimeMillis().toDouble()): String {
        val ts = (timestamp as? JsNum)?.value
        if (ts == null || !ts.isFinite()) return ""
        val minutes = max(0.0, ceil((ts - nowMs) / 60_000))
        if (minutes < 60) return "resets in ${numberToString(minutes)}m"
        val hours = floor(minutes / 60)
        if (hours < 24) return "resets in ${numberToString(hours)}h ${numberToString(minutes % 60)}m"
        return "resets in ${numberToString(floor(hours / 24))}d ${numberToString(hours % 24)}h"
    }

    // lib/format.ts:59 — the finite branch is host-locale formatted on the web (corpus-uncovered).
    // toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }) → en-US "09:05 AM".
    fun clockTime(
        timestamp: JsValue?,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val ts = (timestamp as? JsNum)?.value
        if (ts == null || !ts.isFinite()) return ""
        return DateTimeFormatter.ofPattern("hh:mm a", locale).format(instant(ts, zone))
    }

    // lib/format.ts:64
    fun providerGlyph(provider: JsValue?): String = when ((provider as? JsStr)?.value) {
        "claude" -> "C"
        "codex" -> "X"
        "opencode" -> "O"
        "reasonix" -> "R"
        "pi" -> "P"
        "gemini" -> "G"
        else -> "?"
    }

    fun providerGlyph(provider: String?): String = providerGlyph(if (provider == null) JsNull else JsStr(provider))

    private fun instant(ms: Double, zone: ZoneId) = Instant.ofEpochMilli(ms.toLong()).atZone(zone)

    /** UTC, the corpus environment's zone. */
    val UTC: ZoneId = ZoneOffset.UTC

    // CLDR "en" short compact decimal: K (10^3), M (10^6), B (10^9), T (10^12); nothing larger, so
    // 10^15 prints as "1000T". The value is scaled on its DECIMAL digits (as ICU does), rounded
    // half-expand to one fraction digit, and re-bucketed when rounding carries it to 1000 of a unit.
    private val SUFFIXES = listOf("", "K", "M", "B", "T")

    private fun compactEn(value: Double): String {
        val decimal = BigDecimal(numberToString(kotlin.math.abs(value))) // shortest digits, as ICU sees them
        var unit = if (decimal.signum() == 0) 0 else ((decimal.precision() - decimal.scale() - 1) / 3).coerceIn(0, SUFFIXES.size - 1)
        var rounded = decimal.movePointLeft(3 * unit).setScale(1, RoundingMode.HALF_UP)
        if (rounded >= BigDecimal(1000) && unit < SUFFIXES.size - 1) {
            unit += 1
            rounded = decimal.movePointLeft(3 * unit).setScale(1, RoundingMode.HALF_UP)
        }
        val digits = rounded.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
        // ECMA-402 signDisplay "auto": a sign for every negative number, negative zero included.
        val sign = if (value < 0 || (value == 0.0 && 1 / value < 0)) "-" else ""
        return sign + digits + SUFFIXES[unit]
    }
}
