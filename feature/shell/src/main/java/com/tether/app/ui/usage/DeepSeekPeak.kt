package com.tether.app.ui.usage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.tether.app.protocol.fold.numberToString
import com.tether.app.protocol.helpers.DeepseekPeak
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.rememberTickingNow
import com.tether.app.ui.shell.cssText
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.tabularNums
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * T9.2: DeepSeek API peak / off-peak surfaces (components/deepseek-peak.tsx, globals.css
 * 11140-11225). Every decision is lib/deepseek-peak.mjs's, ported (DeepseekPeak): whether a
 * harness + model is billed by the DeepSeek API, whether an instant is peak, and the words. This
 * file is the clock and the markup: one 30 s ticker per surface (the reading only changes at a
 * window edge and the countdown is minute-granular).
 */

internal object DeepSeekTags {
    const val Badge = "deepseek-peak"
    const val RateCard = "deepseek-rates"
}

/** The pill's two forms: the rail / picker's compact one, the session header's full one. */
enum class DeepSeekPeakVariant { Compact, Full }

/** The shared DeepSeek clock (deepseek-peak.tsx `useDeepSeekClock`, TICK_MS 30 s); a fixed one in goldens. */
@Composable
fun rememberDeepSeekNow(): Long {
    val env = LocalUsageEnv.current
    val ticking = rememberTickingNow(30_000)
    return env.fixedNow ?: ticking
}

/** The words of a reading as Kotlin values (`deepSeekPeakCopy`'s object). */
internal data class PeakCopy(
    val peak: Boolean,
    val reason: String,
    val next: String,
    val why: String,
    val holidayName: String,
    val boundaryAt: Double?,
    val holidayCalendarMissing: Boolean,
    val compact: List<String>,
    val full: List<String>,
)

internal fun peakCopy(now: Long): PeakCopy? {
    val c = DeepseekPeak.deepSeekPeakCopyDate(now.toDouble()) ?: return null
    fun s(k: String) = (c[k] as? JsStr)?.value.orEmpty()
    fun list(k: String) = (c[k] as? JsArr)?.map { (it as JsStr).value }.orEmpty()
    return PeakCopy(
        peak = s("state") == "peak",
        reason = s("reason"),
        next = s("next"),
        why = s("why"),
        holidayName = s("holidayName"),
        boundaryAt = (c["boundaryAt"] as? JsNum)?.value,
        holidayCalendarMissing = c["holidayCalendarMissing"] == JsBool.TRUE,
        compact = list("compact"),
        full = list("full"),
    )
}

/** `usd()`: the published cards carry 1–3 decimals; never padded ("$0.6", "$0.003"). */
internal fun usd(value: Double): String = "$" + numberToString(BigDecimal(numberToString(value)).setScale(4, RoundingMode.HALF_UP).toDouble())

private fun rate(tier: String, column: String, row: String): Double =
    (((DeepseekPeak.DEEPSEEK_RATES[tier] as JsObj)[column] as JsObj)[row] as JsNum).value

private fun tierLabel(tier: String): String = ((DeepseekPeak.DEEPSEEK_RATES[tier] as JsObj)["label"] as JsStr).value

/** A boundary in the viewer's zone, with the weekday when it is not today ("Thu 09:05 AM CEST"). */
internal fun localBoundary(ms: Double, now: Long, env: UsageEnv): String {
    val at = Instant.ofEpochMilli(ms.toLong()).atZone(env.zone)
    val today = Instant.ofEpochMilli(now).atZone(env.zone).toLocalDate()
    val pattern = if (at.toLocalDate() == today) "hh:mm a z" else "EEE hh:mm a z"
    return DateTimeFormatter.ofPattern(pattern, env.locale).format(at)
}

/** The published UTC windows drawn in the viewer's zone for the instant's UTC day ("03:00 AM–06:00 AM, 08:00 AM–12:00 PM CEST"). */
internal fun localWindows(now: Long, env: UsageEnv): String {
    val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    val time = DateTimeFormatter.ofPattern("hh:mm a", env.locale)
    fun at(hour: Int) = day.atTime(hour, 0).atZone(ZoneOffset.UTC).withZoneSameInstant(env.zone)
    val ranges = DeepseekPeak.PEAK_WINDOWS_UTC.joinToString(", ") { (start, end) -> "${time.format(at(start))}–${time.format(at(end))}" }
    val zone = DateTimeFormatter.ofPattern("z", env.locale).format(Instant.ofEpochMilli(now).atZone(env.zone))
    return "$ranges $zone".trim()
}

/** deepseek-peak.tsx `peakTitle`: both rate cards and the windows in the viewer's zone (the tooltip). */
internal fun peakTitle(tier: String, model: String?, copy: PeakCopy, now: Long, env: UsageEnv): String {
    val inForce = if (copy.peak) "peak" else "offPeak"
    val other = if (copy.peak) "offPeak" else "peak"
    fun card(column: String) =
        "${usd(rate(tier, column, "cacheMiss"))} input (cache miss), ${usd(rate(tier, column, "cacheHit"))} input (cache hit), ${usd(rate(tier, column, "output"))} output"
    return listOf(
        "${model?.takeIf { it.isNotEmpty() } ?: tierLabel(tier)} · DeepSeek API · ${if (copy.peak) "peak" else "off-peak"} rate in force",
        copy.why,
        if (copy.next.isNotEmpty() && copy.boundaryAt != null) "${copy.next} (${localBoundary(copy.boundaryAt, now, env)})" else "",
        "Now, per 1M tokens: ${card(inForce)}",
        "${if (copy.peak) "Off-peak" else "Peak"}: ${card(other)}",
        "Peak hours: 01:00–04:00 and 06:00–10:00 UTC (${localWindows(now, env)}), Monday–Friday, except Chinese public holidays.",
        if (copy.holidayCalendarMissing) "No Chinese public-holiday calendar is on file for this year, so no holiday is exempted from peak in this reading." else "",
    ).filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * `DeepSeekPeakBadge`: the peak / off-peak pill for a harness + model, or nothing when that pair is
 * not billed by the DeepSeek API (the gate runs before any clock). The state word is always printed
 * (never colour alone); peak takes `--warning` on its border, dot and words. A long press shows the
 * web's tooltip (both rate cards and the windows in this zone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSeekPeakBadge(provider: String?, model: String?, variant: DeepSeekPeakVariant = DeepSeekPeakVariant.Compact, modifier: Modifier = Modifier) {
    val tier = DeepseekPeak.deepSeekApiTier(provider?.let(::JsStr), model?.let(::JsStr)) ?: return
    val now = rememberDeepSeekNow()
    val env = LocalUsageEnv.current
    val copy = peakCopy(now) ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val parts = if (variant == DeepSeekPeakVariant.Full) copy.full else copy.compact
    val label = parts.firstOrNull().orEmpty()
    val rest = parts.drop(1)
    val compact = variant == DeepSeekPeakVariant.Compact
    val ink = if (copy.peak) t.warning else t.muted
    val detailInk = if (copy.peak) t.warning else t.faint
    val rem = if (compact) 0.6f else 0.66f
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(peakTitle(tier, model, copy, now, env)) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .cssSurface(RoundedCornerShape(percent = 50), Color.Transparent, CssBorder(1.dp, if (copy.peak) t.warning else t.line))
                .padding(1.dp)
                .padding(horizontal = (if (compact) 0.36f else 0.44f).times(16).dp, vertical = (if (compact) 0.02f else 0.06f).times(16).dp)
                .clearAndSetSemantics { contentDescription = "DeepSeek API rate: " + (listOf(label) + rest).joinToString(", ") }
                .testTag(DeepSeekTags.Badge),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((if (compact) 0.26f else 0.3f).times(16).dp),
        ) {
            Box(Modifier.size(6.4.dp).cssSurface(CircleShape, if (copy.peak) t.warning else t.faint))
            Text(label, color = ink, maxLines = 1, style = cssText(type.ui, rem, 560, lineHeight = 1.6f))
            if (rest.isNotEmpty()) {
                Text(
                    rest.joinToString("") { " · $it" }.removePrefix(" "),
                    color = detailInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = cssText(type.ui, rem, 560, lineHeight = 1.6f).tabularNums(),
                    modifier = Modifier.padding(start = 0.dp),
                )
            }
        }
    }
}

/**
 * `DeepSeekRateCard` (the Accounts dialog's DeepSeek section): the published card with the column in
 * force marked by the word "now" as well as the tint, its status line, and the source note. Before
 * the clock exists no column is in force.
 */
@Composable
fun DeepSeekRateCard(modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val env = LocalUsageEnv.current
    val now = rememberDeepSeekNow()
    val copy = peakCopy(now)
    val inForce = copy?.let { if (it.peak) "peak" else "off-peak" }
    val status = when {
        copy == null -> ""
        copy.peak -> "Peak rates apply now · ${copy.next}"
        else -> {
            val why = when {
                copy.holidayName.isNotEmpty() -> " (${copy.holidayName})"
                copy.reason == "weekend" -> " (weekend)"
                copy.reason == "holiday" -> " (Chinese public holiday)"
                else -> ""
            }
            "Off-peak rates apply now$why${if (copy.next.isNotEmpty()) " · ${copy.next}" else ""}"
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .cssSurface(RoundedCornerShape(t.radiusMd), t.graphiteRaised, CssBorder(1.dp, t.line))
            .padding(1.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(DeepSeekTags.RateCard),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.Bottom) {
            Text("Live pricing", color = t.white, style = cssText(type.ui, 0.85f, 650))
            if (status.isNotEmpty()) Text(status, color = if (copy?.peak == true) t.warning else t.muted, style = cssText(type.ui, 0.68f, 400).tabularNums())
        }
        RateTable(inForce)
        Text(
            buildAnnotatedString {
                val code = SpanStyle(fontFamily = type.mono, fontSize = 0.95.em)
                append("Off-peak rates are half of peak rates. Peak hours are 01:00–04:00 and 06:00–10:00 UTC (${localWindows(now, env)}), Monday–Friday, except Chinese public holidays; weekends and those holidays are off-peak in full. ")
                withStyle(code) { append("deepseek-v4-flash") }
                append(" is a legacy name billed at the ")
                withStyle(code) { append("deepseek-flash") }
                append(" price, which applies since 04:00 UTC on 10 Sep 2026 — earlier usage was billed on an older card, so Usage labels it an estimate. Source: api-docs.deepseek.com/quick_start/pricing (verified 2026-09-25).")
            },
            color = t.faint,
            style = cssText(type.ui, 0.64f, 400, lineHeight = 1.5f),
        )
    }
}

/** `.deepseek-rates-table`: a row label column, then off-peak / peak under each card; ≥ 21rem, scrolling sideways. */
@Composable
private fun RateTable(inForce: String?) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val tiers = listOf("flash", "pro")
    val cell = cssText(type.ui, 0.7f, 400).tabularNums()
    val head = cssText(type.ui, 0.7f, 600)
    val now = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 0.85.em, fontStyle = FontStyle.Normal, fontWeight = FontWeight(700), letterSpacing = 0.03.em)) { append(" NOW") }
    }
    fun Modifier.rule() = drawBehind { drawRect(t.line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
    fun Modifier.current(on: Boolean) = if (on) cssSurface(androidx.compose.ui.graphics.RectangleShape, t.slate) else this
    val pad = Modifier.padding(horizontal = 8.dp, vertical = 5.12.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.widthIn(min = 336.dp).width(IntrinsicSize.Max)) {
            Row(Modifier.fillMaxWidth().rule()) {
                Text("USD / 1M tokens", color = t.faint, style = head, textAlign = TextAlign.End, modifier = Modifier.weight(1.6f).then(pad))
                tiers.forEach { tier -> Text(tierLabel(tier), color = t.faint, style = head, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(2f).then(pad)) }
            }
            Row(Modifier.fillMaxWidth().rule().height(IntrinsicSize.Min)) {
                Box(Modifier.weight(1.6f).semantics { contentDescription = "Rate" })
                tiers.forEach { _ ->
                    listOf("off-peak", "peak").forEach { column ->
                        val on = inForce == column
                        Text(
                            buildAnnotatedString {
                                append(if (column == "peak") "Peak" else "Off-peak")
                                if (on) append(now)
                            },
                            color = if (on) t.white else t.faint,
                            style = head,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).current(on).then(pad),
                        )
                    }
                }
            }
            listOf("cacheHit" to "1M input tokens (cache hit)", "cacheMiss" to "1M input tokens (cache miss)", "output" to "1M output tokens").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().rule().height(IntrinsicSize.Min)) {
                    Text(label, color = t.muted, style = cssText(type.ui, 0.7f, 500), modifier = Modifier.weight(1.6f).then(pad))
                    tiers.forEach { tier ->
                        listOf("off-peak" to "offPeak", "peak" to "peak").forEach { (column, field) ->
                            val on = inForce == column
                            Text(
                                usd(rate(tier, field, key)),
                                color = if (on) t.white else t.muted,
                                style = cell,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).current(on).then(pad),
                            )
                        }
                    }
                }
            }
        }
    }
}

