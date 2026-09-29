package com.tether.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.util.statusCopy
import kotlinx.coroutines.delay

/**
 * T13.2 (SYNC_DESIGN §4.1-4.2): the freshness marks. A NATIVE-ONLY surface: the web has no saved
 * copies, so there is no web reference for any of it.
 *
 * Rules every mark here keeps:
 * - never colour alone: each state is a Lucide glyph AND words, and the words are the TalkBack
 *   label;
 * - neutral ink only (`--muted` / `--faint` on `--graphite-raised` with a `--line` edge): violet
 *   means focus, selected or waiting-on-you, and red means an error. Stale is neither;
 * - Live is the unmarked default ([FreshnessCopy.sessionLabel] is null);
 * - a saved copy never claims the agent is doing anything now ([FreshnessCopy.qualifiedStatus]).
 */
object FreshnessCopy {
    const val CATCHING_UP = "Catching up…"
    const val SAVED = "Saved copy"
    const val NOT_DOWNLOADED = "Not downloaded. Connect to load"
    const val OLDER_TURNS = "Older turns not downloaded"
    const val OFFLINE = "Offline. Showing saved copies"
    const val RECONNECTING = "Reconnecting…"

    /** "just now" / "12 min ago" / "3 hr ago" / "1 day ago" / "4 days ago"; null when unknown. */
    fun age(verifiedAt: Long?, now: Long): String? {
        if (verifiedAt == null) return null
        val minutes = ((now - verifiedAt).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} hr ago"
            else -> (minutes / (24 * 60)).let { d -> if (d == 1L) "1 day ago" else "$d days ago" }
        }
    }

    /** The sidebar's short age: "now" / "12m" / "3h" / "2d"; null when unknown. */
    fun shortAge(verifiedAt: Long?, now: Long): String? {
        if (verifiedAt == null) return null
        val minutes = ((now - verifiedAt).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    /** The session mark's words (also its TalkBack label); null for Live, the unmarked default. */
    fun sessionLabel(sync: SessionSync?, now: Long): String? = when (sync?.freshness) {
        null, Freshness.Live -> null
        Freshness.CatchingUp -> CATCHING_UP
        Freshness.Saved -> age(sync.lastVerifiedAt, now)?.let { "$SAVED · updated $it" } ?: SAVED
        Freshness.NotDownloaded -> NOT_DOWNLOADED
    }

    /**
     * A run badge read from a copy that is not live (SYNC_DESIGN §4.2): "Was running · 12 min ago",
     * "Was waiting on you · 12 min ago". Null for a status that claims nothing about now (ready,
     * ended), which keeps its ordinary words.
     */
    fun qualifiedStatus(status: String, verifiedAt: Long?, now: Long): String? {
        val was = when (status) {
            "active" -> "Was running"
            "waiting" -> "Was waiting on you"
            else -> return null
        }
        return age(verifiedAt, now)?.let { "$was · $it" } ?: was
    }

    /**
     * The status pill of a session: its ordinary words and tone while [listLive] (the session list
     * is current on a live connection), else [qualifiedStatus] on a faint, still dot: never the
     * running spinner or the violet waiting ping.
     */
    fun statusPill(status: String, listLive: Boolean, verifiedAt: Long?, now: Long): Pair<String, StatusTone> {
        if (!listLive) qualifiedStatus(status, verifiedAt, now)?.let { return it to StatusTone.History }
        return statusCopy(status) to statusToneOf(status)
    }
}

/** The link-level banner (SYNC_DESIGN §4.1): shown only while not connected. */
enum class LinkBanner(val label: String) {
    Offline(FreshnessCopy.OFFLINE),
    Reconnecting(FreshnessCopy.RECONNECTING),
}

/** The glyph of a freshness state (null for Live). */
fun freshnessIcon(freshness: Freshness?): ImageVector? = when (freshness) {
    null, Freshness.Live -> null
    Freshness.CatchingUp -> TetherIcons.RefreshCw
    Freshness.Saved -> TetherIcons.History
    Freshness.NotDownloaded -> TetherIcons.CloudOff
}

/** A wall clock for the relative ages, ticking every [periodMs] while composed. */
@Composable
fun rememberTickingNow(periodMs: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The refresh glyph turns (1 s a turn, static under reduced motion); the others are still. */
@Composable
private fun MarkIcon(icon: ImageVector, tint: Color, size: Dp) {
    if (icon == TetherIcons.RefreshCw) SpinningIcon(icon, tint, size) else Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/**
 * The session's freshness mark (chat header, transcript): an etched neutral pill, glyph + words.
 * Nothing for Live. TalkBack reads the words.
 */
@Composable
fun FreshnessChip(sync: SessionSync?, now: Long, modifier: Modifier = Modifier) {
    val label = FreshnessCopy.sessionLabel(sync, now) ?: return
    val icon = freshnessIcon(sync?.freshness) ?: return
    FreshnessPill(icon, label, modifier)
}

/** [FreshnessChip]'s pill for any glyph and words (e.g. "Older turns not downloaded"). */
@Composable
fun FreshnessPill(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = label }
            .heightIn(min = 24.dp)
            .cssSurface(shape, t.graphiteRaised, if (t.skin.family == com.tether.app.ui.theme.ThemeFamily.Studio) null else CssBorder(1.dp, t.line))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MarkIcon(icon, t.muted, 12.dp)
        Text(
            label,
            style = type.body.copy(fontSize = 11.sp, fontWeight = FontWeight(560)),
            color = t.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The sidebar row's glyph (SYNC_DESIGN §4.2): `history` + a short age ("12m") for a saved copy,
 * `cloud-off` for one not downloaded. TalkBack reads the full sentence. Nothing for Live.
 */
@Composable
fun FreshnessGlyph(sync: SessionSync?, now: Long, modifier: Modifier = Modifier) {
    val label = FreshnessCopy.sessionLabel(sync, now) ?: return
    val icon = freshnessIcon(sync?.freshness) ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val short = if (sync?.freshness == Freshness.Saved) FreshnessCopy.shortAge(sync.lastVerifiedAt, now) else null
    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MarkIcon(icon, t.faint, 12.dp)
        if (short != null) Text(short, style = type.body.copy(fontSize = 10.9.sp, fontWeight = FontWeight(560)), color = t.faint, maxLines = 1, softWrap = false)
    }
}

/**
 * The global link banner: a full-width strip, never a modal, `wifi-off` + "Offline. Showing saved
 * copies" or `refresh-cw` + "Reconnecting…". A polite live region, so TalkBack announces a change.
 */
@Composable
fun ConnectionBanner(banner: LinkBanner, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    Box(
        modifier
            .fillMaxWidth()
            .background(t.graphiteRaised)
            .semantics(mergeDescendants = true) {
                contentDescription = banner.label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = t.css.spaceLg, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkIcon(if (banner == LinkBanner.Offline) TetherIcons.WifiOff else TetherIcons.RefreshCw, t.muted, 14.dp)
            Text(
                banner.label,
                style = type.body.copy(fontSize = 12.sp, fontWeight = FontWeight(600)),
                color = t.ink,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(t.line))
    }
}
