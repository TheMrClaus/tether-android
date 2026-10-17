package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * W50-R1: the web's `messageStamp` (lib/message-time.ts at 19c56e25), with the hidden inputs made explicit:
 * "HH:MM" when [ts] falls on the same LOCAL calendar day as [nowMs]; otherwise a locale short-date prefix
 * ("Oct 5, 14:32", and with the year for another year: "Oct 5, 2025, 14:32"). "" for a missing / non-finite /
 * out-of-range ts (never a fallback time). [datePattern] gives the locale's best pattern for a skeleton
 * (the browser's `toLocaleDateString(undefined, ...)`); the default is Android's.
 */
internal fun messageStamp(
    ts: Double?,
    nowMs: Long,
    zone: ZoneId,
    locale: Locale,
    datePattern: (Locale, String) -> String = { l, skeleton -> android.text.format.DateFormat.getBestDateTimePattern(l, skeleton) },
): String {
    if (ts == null || !ts.isFinite() || kotlin.math.abs(ts) > 8.64e15) return ""
    val date = Instant.ofEpochMilli(ts.toLong()).atZone(zone)
    val clock = "%02d:%02d".format(Locale.ROOT, date.hour, date.minute)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone)
    if (date.toLocalDate() == today.toLocalDate()) return clock
    val skeleton = if (date.year == today.year) "MMMd" else "yMMMd"
    return "${DateTimeFormatter.ofPattern(datePattern(locale, skeleton), locale).format(date)}, $clock"
}

/** The clock and locale a stamp reads; the screenshot harness pins both next to the zone. */
internal class StampClock(val nowMs: () -> Long, val locale: () -> Locale)

internal val LocalStampClock = staticCompositionLocalOf { StampClock({ System.currentTimeMillis() }, { Locale.getDefault() }) }

/** `.chat-outcome-time`: the muted 0.65rem stamp (tabular figures), none when [ts] has none. */
@Composable
internal fun OutcomeStamp(ts: Double?, zone: ZoneId) {
    val text = outcomeStampText(ts, zone)
    if (text.isEmpty()) return
    Text(
        text,
        color = LocalTetherTokens.current.muted,
        fontFamily = Manrope,
        fontWeight = TetherWeights.body,
        fontSize = 10.4.sp,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
    )
}

@Composable
internal fun outcomeStampText(ts: Double?, zone: ZoneId): String {
    val clock = LocalStampClock.current
    return messageStamp(ts, clock.nowMs(), zone, clock.locale())
}
