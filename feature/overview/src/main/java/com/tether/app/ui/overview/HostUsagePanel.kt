package com.tether.app.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.tether.app.client.ConnectionState
import com.tether.app.client.HostMetrics
import com.tether.app.client.OverviewMetricsResult
import com.tether.app.client.OverviewUsage
import com.tether.app.client.TetherClient
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.FreshnessPill
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.rememberTickingNow
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags of the tile. */
object HostUsageTags {
    const val Panel = "overview-host-usage-panel"
    const val Notice = "overview-host-notice"
    const val Freshness = "overview-host-freshness"
    const val Scope = "overview-host-scope"
    const val Tokens = "overview-host-tokens"
    const val UsageNote = "overview-host-usage-note"
    const val DiskNote = "overview-host-disk-note"
    const val ViewUsage = "overview-host-view-usage"
    fun meter(label: String) = "overview-host-meter:$label"
}

/**
 * T15.3: overview-host.tsx `HostUsageTile` wired to the client — `useJsonPoll` for the two routes:
 *
 * - fetched only while this is composed (the Overview is on screen) AND the app is started
 *   (ON_START..ON_STOP, the native `document.hidden`), AND signed in; host every 5 s, usage every
 *   15 s; returning (ON_START, a new server) fetches at once, as the web's `visibilitychange` does;
 * - the readings belong to ONE server: a new [TetherClient.serverUrl] or a sign-out starts them
 *   from nothing, the old polls are cancelled (and their sockets with them), and
 *   [HostUsageModel.fold] refuses to keep a value beside another origin's answer;
 * - one request at a time per route (the next waits for the last: no pile-up on a slow link).
 *
 * [onViewUsage] is the web's "View usage" link; null (no Usage screen yet) draws none.
 * [clock] is a test seam.
 */
@Composable
fun HostUsageHost(
    client: TetherClient,
    modifier: Modifier = Modifier,
    onViewUsage: (() -> Unit)? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    val server by client.serverUrl.collectAsStateWithLifecycle()
    val configured by client.configured.collectAsStateWithLifecycle()
    val connection by client.connection.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)

    // Keyed on the server and the sign-in: another server (or none) starts from nothing.
    var host by remember(server, configured) { mutableStateOf(MetricsReading<HostMetrics>()) }
    var usage by remember(server, configured) { mutableStateOf(MetricsReading<OverviewUsage>()) }

    LaunchedEffect(client, server, configured, started) {
        if (!started || !configured || server == null) return@LaunchedEffect
        val source = client.overviewMetrics
        launch { poll(HostUsageModel.HOST_POLL_MS, { source.host() }) { host = HostUsageModel.fold(host, it, clock()) } }
        launch { poll(HostUsageModel.USAGE_POLL_MS, { source.usage() }) { usage = HostUsageModel.fold(usage, it, clock()) } }
    }

    // Re-read [clock] every poll period (and whenever a reading lands), so a reading goes stale on time.
    val tick = rememberTickingNow(HostUsageModel.HOST_POLL_MS)
    val now = remember(tick, host, usage) { clock() }
    HostUsagePanel(
        tile = HostUsagePresentation.tile(host, usage, now, connected = connection == ConnectionState.Connected),
        modifier = modifier,
        onViewUsage = onViewUsage,
    )
}

/** Fetch at once, then every [intervalMs] after the last answer, until cancelled. */
private suspend fun <T> poll(intervalMs: Long, fetch: suspend () -> OverviewMetricsResult<T>, onResult: (OverviewMetricsResult<T>) -> Unit) {
    while (true) {
        onResult(fetch())
        delay(intervalMs)
    }
}

/**
 * overview-host.tsx `HostUsageTile`'s markup in the Overview's panel (overview.module.css
 * `.resources`): the head (title, scope), the freshness pill, the notice, the three meters (one
 * column below ~22.5rem, as the web), the disk note, then the usage row. Stateless over [tile].
 */
@Composable
fun HostUsagePanel(tile: HostUsagePresentation.Tile, modifier: Modifier = Modifier, onViewUsage: (() -> Unit)? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Panel(HostUsageTags.Panel, attention = false, modifier = modifier) {
        PanelTitle(HostUsagePresentation.TITLE) {
            Text(
                tile.scope,
                style = css(type.ui, 0.78f, 400),
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Readings are ${tile.scope}, for this Tether node only" }
                    .testTag(HostUsageTags.Scope),
            )
        }
        tile.freshness?.let { f ->
            FreshnessPill(
                if (f.offline) TetherIcons.WifiOff else TetherIcons.History,
                f.label,
                Modifier.padding(bottom = 10.dp).testTag(HostUsageTags.Freshness),
            )
        }
        tile.notice?.let { Notice(it) }
        Meters(tile)
        tile.diskNote?.let {
            Text(it, style = css(type.ui, 0.75f, 400), color = t.faint, modifier = Modifier.padding(top = 8.dp).testTag(HostUsageTags.DiskNote))
        }
        // `.usageRow`: a rule, then the figure and the link.
        Box(Modifier.padding(top = 14.4.dp).fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), t.line))
        Row(
            Modifier.fillMaxWidth().padding(top = 14.4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) { }
                    .testTag(HostUsageTags.Tokens),
                verticalArrangement = Arrangement.spacedBy(3.2.dp),
            ) {
                Text(tile.tokensLabel, style = css(type.ui, 0.8f, 600), color = t.muted)
                Text(tile.tokensValue, style = css(type.mono, 1.05f, 550).copy(fontFeatureSettings = "tnum"), color = t.white)
                Text(tile.usageNote, style = css(type.ui, 0.75f, 400), color = t.faint, modifier = Modifier.testTag(HostUsageTags.UsageNote))
            }
            if (onViewUsage != null) {
                Row(
                    Modifier
                        .heightIn(min = TetherDimens.touchTargetDp)
                        .clickable(role = Role.Button, onClick = onViewUsage)
                        .testTag(HostUsageTags.ViewUsage),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.4.dp),
                ) {
                    Text("View usage", style = css(type.ui, 0.86f, 650), color = t.violet)
                    Icon(TetherIcons.ArrowRight, contentDescription = null, tint = t.violet, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** The web's `.panelError` line, or (native) the T6.8 blocked notice in the neutral idiom of the transcript's. */
@Composable
private fun Notice(notice: HostUsagePresentation.Notice) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    if (!notice.blocked) {
        Text(
            notice.text,
            style = css(type.ui, 0.8f, 400),
            color = t.danger,
            modifier = Modifier.padding(bottom = 8.dp).testTag(HostUsageTags.Notice),
        )
        return
    }
    Row(
        Modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .cssSurface(RoundedCornerShape(t.radiusSm), t.tintXs)
            .semantics(mergeDescendants = true) { }
            .testTag(HostUsageTags.Notice)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(TetherIcons.ShieldAlert, contentDescription = null, tint = t.muted, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notice.text, style = css(type.ui, 0.8f, 600), color = t.ink)
            notice.detail?.let { Text(it, style = css(type.ui, 0.75f, 400, lineHeight = 1.4f), color = t.muted) }
        }
    }
}

/** `.meters`: three columns split by a rule, one column (rules between rows) when narrow. */
@Composable
private fun Meters(tile: HostUsagePresentation.Tile) {
    val t = LocalTetherTokens.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .semantics { if (tile.busy) stateDescription = "Loading" },
    ) {
        // overview.module.css `@media (max-width: 22.5rem)`: a 360dp screen leaves this much inside the panel.
        val width = maxWidth
        val stacked = width < 300.dp
        if (stacked) {
            Column {
                tile.meters.forEachIndexed { index, meter ->
                    if (index > 0) Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).cssSurface(RoundedCornerShape(0.dp), t.line))
                    MeterCell(meter, narrow = true, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tile.meters.forEachIndexed { index, meter ->
                    if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().cssSurface(RoundedCornerShape(0.dp), t.line))
                    MeterCell(meter, narrow = width < 420.dp, Modifier.weight(1f))
                }
            }
        }
    }
}

/** `Meter`: label, track (role=meter: 0..100, the printed figure as its value text), value or reason. */
@Composable
private fun MeterCell(meter: HostUsagePresentation.Meter, narrow: Boolean, modifier: Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val fill: Color = when (meter.tone) {
        HostUsagePresentation.Tone.Danger -> t.danger
        HostUsagePresentation.Tone.Warning -> t.warning
        else -> t.violet
    }
    Column(
        modifier
            .testTag(HostUsageTags.meter(meter.label))
            .clearAndSetSemantics {
                contentDescription = meter.label
                stateDescription = meter.spoken
                meter.clamped?.let { progressBarRangeInfo = ProgressBarRangeInfo(it.toFloat(), 0f..100f) }
            },
        verticalArrangement = Arrangement.spacedBy(7.2.dp),
    ) {
        Text(meter.label, style = css(type.ui, 0.8f, 600), color = t.muted, maxLines = 1)
        Box(Modifier.fillMaxWidth().height(6.4.dp).cssSurface(RoundedCornerShape(percent = 50), t.slate)) {
            val clamped = meter.clamped
            if (clamped != null && clamped > 0) {
                Box(Modifier.fillMaxWidth((clamped / 100).toFloat()).fillMaxHeight().cssSurface(RoundedCornerShape(percent = 50), fill))
            }
        }
        if (meter.unavailable != null) {
            Text(meter.unavailable, style = css(type.ui, 0.8f, 500), color = t.faint)
        } else {
            Text(
                meter.value,
                style = css(type.mono, if (narrow) 0.82f else 0.95f, 550).copy(fontFeatureSettings = "tnum"),
                color = t.white,
                maxLines = if (narrow) 2 else 1,
            )
        }
    }
}
