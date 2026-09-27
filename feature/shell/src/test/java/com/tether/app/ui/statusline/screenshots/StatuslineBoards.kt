package com.tether.app.ui.statusline.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.SessionView
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.evNullTurn
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.ui.statusline.ContextGauge
import com.tether.app.ui.statusline.ReadingEnv
import com.tether.app.ui.statusline.SessionDial
import com.tether.app.ui.statusline.SessionStatusline
import com.tether.app.ui.statusline.TelemetryMetrics
import com.tether.app.ui.statusline.TelemetryWindow
import com.tether.app.ui.statusline.WrapUpNotice
import com.tether.app.ui.statusline.WrapUpPill
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** The boards' fixed clock and host locale (UTC, en-US), so every printed time is reproducible. */
const val BoardNow: Long = 1_790_078_400_000
val BoardEnv: () -> ReadingEnv = { ReadingEnv(BoardNow.toDouble(), Locale.US, ZoneOffset.UTC) }

private val full = TelemetryMetrics(
    contextPercent = 45.0,
    contextTokens = 90_400.0,
    contextWindow = 200_000.0,
    fiveHour = TelemetryWindow(41.0, BoardNow + 125 * 60_000.0),
    weekly = TelemetryWindow(18.0, BoardNow + 27 * 3_600_000.0),
)

/** A folded projection: a turn in flight, an optional wrap-up allowance, a todo list mid-way. */
fun boardState(grace: String? = null, todo: Boolean = true): SessionView = SessionView(
    foldTree(
        freshTree(),
        *buildList {
            add(ev("turn_started", "t1", seq = 1, ts = 1_790_000_001_000))
            add(
                ev("rate_limit", "t1", seq = 2, ts = 1_790_000_002_000) {
                    put("status", "allowed_warning")
                    put("resetsAt", BoardNow + 95 * 60_000)
                    if (grace != null) put("grace", grace)
                },
            )
            if (todo) {
                add(
                    evNullTurn("todo_updated", seq = 3, ts = 1_790_000_003_000) {
                        putJsonArray("items") {
                            addJsonObject { put("content", "Read"); put("activeForm", "Reading the suite"); put("status", "completed") }
                            addJsonObject { put("content", "Fix"); put("activeForm", "Fixing the flaky parser test"); put("status", "in_progress") }
                            addJsonObject { put("content", "Run"); put("activeForm", "Running the suite"); put("status", "pending") }
                        }
                    },
                )
            }
        }.toTypedArray(),
    ),
)

/** A container of [width] on the popover's `--graphite` panel, so each strip's bounds are visible. */
@Composable
private fun Strip(caption: String, width: Dp, content: @Composable () -> Unit) {
    val t = LocalTetherTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(caption, color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
        Box(Modifier.width(width).background(t.graphite).padding(vertical = 4.dp)) { content() }
    }
}

/**
 * session-statusline: every segment, the phone popover (348dp = 380dp popover − 2·16dp padding:
 * 21.75rem, so the rank-4 task drops) vs 22rem+, the container-query drops at narrow widths, the
 * tones, the snapshot footprint, the wrap-up rank-0 segment and a single-row (nowrap) strip whose
 * elastic task ellipsises.
 */
@Composable
fun ColumnScope.StatuslineBoard() {
    val busy = boardState(grace = "wrap_up")
    val calm = boardState()
    Strip("phone popover 348dp · wrap-up ctx 5h wk (task drops < 22rem)", 348.dp) { SessionStatusline(full, busy, env = BoardEnv) }
    Strip("tablet popover 352dp (22rem), start-aligned · all five", 352.dp) {
        SessionStatusline(full, busy, horizontalArrangement = Arrangement.Start, env = BoardEnv)
    }
    Strip("8rem · rank 0 only, no bar", 128.dp) { SessionStatusline(full, calm, env = BoardEnv) }
    Strip("10rem · two ranks, bar back", 160.dp) { SessionStatusline(full, calm, env = BoardEnv) }
    Strip("16rem · four ranks", 256.dp) { SessionStatusline(full, calm, env = BoardEnv) }
    Strip("tones · 82% high", 348.dp) { SessionStatusline(full.copy(contextPercent = 82.0), null, env = BoardEnv) }
    Strip("tones · 112% over → 100% critical", 348.dp) {
        SessionStatusline(full.copy(contextPercent = 112.0, contextTokens = 224_000.0), null, env = BoardEnv)
    }
    Strip("unknown window · snapshot footprint", 348.dp) {
        SessionStatusline(TelemetryMetrics(contextTokens = 91_540.0, contextSnapshotAt = 1_767_258_307_000.0, weekly = TelemetryWindow(7.0)), null, env = BoardEnv)
    }
    Strip("no context · weekly + task promoted", 348.dp) {
        SessionStatusline(TelemetryMetrics(weekly = TelemetryWindow(12.0)), calm, env = BoardEnv)
    }
    Strip("single row 300dp · elastic task ellipsises", 300.dp) {
        SessionStatusline(TelemetryMetrics(contextPercent = 45.0), calm, wrap = false, env = BoardEnv)
    }
    Strip("no reading · renders nothing", 348.dp) { SessionStatusline(null, null, env = BoardEnv) }
}

/** session-dial: live (ink + running dot), ended (muted, frozen), hours past 99. */
@Composable
fun ColumnScope.DialBoard() {
    val clock = { BoardNow }
    StateRow("live · ended · long") {
        SessionDial(BoardNow - 540_000, null, active = true, clock = clock)
        SessionDial(BoardNow - 9_000_000, BoardNow - 5_276_000, active = false, clock = clock)
        SessionDial(BoardNow - 360_000_000, null, active = true, clock = clock)
    }
}

/** context-gauge: the needle's levels and tones, then the key's states and the labelled form. */
@Composable
fun ColumnScope.GaugeBoard() {
    fun m(p: Double?) = TelemetryMetrics(contextPercent = p, contextTokens = 90_000.0, contextWindow = 200_000.0)
    StateRow("none · 0% · 45% · 80% high · 95% critical · 112% over") {
        for (p in listOf(null, 0.0, 45.0, 80.0, 95.0, 112.0)) ContextGauge(m(p), pressed = false, onClick = {}, env = BoardEnv)
    }
    StateRow("rest · pressed · open (drawer handle)") {
        ContextGauge(m(45.0), pressed = false, onClick = {}, env = BoardEnv)
        ContextGauge(m(45.0), pressed = false, onClick = {}, interactionSource = heldPress(), env = BoardEnv)
        ContextGauge(m(45.0), pressed = true, onClick = {}, env = BoardEnv)
    }
    HeaderSurface {
        StateRow("on the header's --graphite · no reading (the web scenarios' seed) · rest · open") {
            ContextGauge(null, pressed = false, onClick = {}, env = BoardEnv)
            ContextGauge(null, pressed = true, onClick = {}, env = BoardEnv)
        }
    }
    StateRow("labelled (≥48rem) · rest · open") {
        ContextGauge(m(45.0), showLabel = true, pressed = false, onClick = {}, env = BoardEnv)
        ContextGauge(m(45.0), showLabel = true, pressed = true, onClick = {}, env = BoardEnv)
    }
}

/** The workspace header's surface (`.workspace-header { background: var(--graphite) }`), where the dial and gauge live. */
@Composable
private fun HeaderSurface(content: @Composable () -> Unit) {
    Box(Modifier.background(LocalTetherTokens.current.graphite).padding(8.dp)) { content() }
}

/** The wrap-up badge: the inspector notice (both variants) and the pill. */
@Composable
fun ColumnScope.WrapUpBoard() {
    StateRow("pill") { WrapUpPill() }
    Strip("notice · wrap_up, reset known", 348.dp) { WrapUpNotice(boardState(grace = "wrap_up"), env = BoardEnv) }
    Strip("notice · wrap_up_then_credits", 348.dp) { WrapUpNotice(boardState(grace = "wrap_up_then_credits"), env = BoardEnv) }
    Strip("no allowance · renders nothing", 348.dp) { WrapUpNotice(boardState(), env = BoardEnv) }
}

/** The tablet header rail as the web draws it at 1280×800: dial + labelled gauge (streaming). */
@Composable
fun ColumnScope.HeaderRailBoard() {
    HeaderSurface {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val t = LocalTetherTokens.current
            Text("on the header's --graphite · dial + gauge (no reading), labelled", color = t.faint, style = TextStyle(fontFamily = LocalTetherTypography.current.mono, fontSize = 10.sp))
            // `.workspace-actions { gap: var(--space-sm) }` (globals.css 3272, 11193).
            Row(horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm), verticalAlignment = Alignment.CenterVertically) {
                SessionDial(BoardNow - 540_000, null, active = true, clock = { BoardNow })
                ContextGauge(null, showLabel = true, pressed = false, onClick = {}, env = BoardEnv)
            }
        }
    }
}

val StatuslineBoards: Map<String, @Composable ColumnScope.() -> Unit> = linkedMapOf(
    "statusline" to { StatuslineBoard() },
    "session-dial" to { DialBoard() },
    "context-gauge" to { GaugeBoard() },
    "wrap-up" to { WrapUpBoard() },
)
