package com.tether.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.client.Freshness
import com.tether.app.client.SessionSync
import com.tether.app.ui.components.FreshnessCopy
import com.tether.app.ui.components.FreshnessPill
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherWeights

/**
 * T13.2 (SYNC_DESIGN §4.1-4.2; native-only, no web reference): what the chat shows about how
 * current its copy is.
 */
internal object ChatFreshness {
    const val NOT_DOWNLOADED_TAG = "chat-not-downloaded"
    const val OLDER_TURNS_TAG = "chat-older-turns-not-downloaded"

    /**
     * SYNC_DESIGN §4.2 wired into T6.3's lock: a session is "live" for its cards, Stop and Interrupt
     * keys and controls only when the client confirmed it on this connection ([liveSessions]) AND its
     * freshness says Live. The two agree except for an instant after a change; taking both is
     * strictly more restrictive, so a saved, catching-up or not-downloaded copy is never actionable.
     * r2: a missing entry is locked whenever the client [reportsFreshness]; only a client that
     * reports none keeps the T6.3 rule alone ([LiveCopy.isLive]).
     */
    fun isLive(sessionId: String?, liveSessions: Set<String>, sync: SessionSync?, reportsFreshness: Boolean): Boolean =
        com.tether.app.client.LiveCopy.isLive(sessionId, liveSessions, sync, reportsFreshness)

    /**
     * r2 (SYNC_DESIGN §4.2): what the composer's turn readings stand on. Null while the copy is live;
     * otherwise the copy's freshness (a missing entry reads as a saved copy of unknown age), so the
     * run row says "Was running" on a still dot and its timer stops.
     */
    fun staleCopy(live: Boolean, sync: SessionSync?): SessionSync? =
        if (live) null else sync?.takeIf { it.freshness != Freshness.Live } ?: SessionSync(Freshness.Saved, sync?.lastVerifiedAt)

    /** A saved copy cannot fetch its trimmed turns ("Older turns not downloaded"). */
    fun olderTurnsUnavailable(sync: SessionSync?): Boolean =
        sync?.freshness == Freshness.Saved || sync?.freshness == Freshness.NotDownloaded
}

/** True while the transcript's "Load N earlier turns" cannot load (read by its row). */
internal val LocalOlderTurnsUnavailable = compositionLocalOf { false }

/** The transcript's "Older turns not downloaded" row, in place of the load-earlier key. */
@Composable
internal fun OlderTurnsNotDownloaded() {
    Box(Modifier.fillMaxWidth().testTag(ChatFreshness.OLDER_TURNS_TAG), contentAlignment = Alignment.Center) {
        FreshnessPill(TetherIcons.CloudOff, FreshnessCopy.OLDER_TURNS)
    }
}

/** A session with nothing on the device, while offline: `cloud-off` + "Not downloaded. Connect to load". */
@Composable
internal fun SessionNotDownloaded() {
    val t = LocalTetherTokens.current
    Column(
        Modifier
            .fillMaxSize()
            .testTag(ChatFreshness.NOT_DOWNLOADED_TAG)
            .semantics(mergeDescendants = true) { contentDescription = FreshnessCopy.NOT_DOWNLOADED },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(TetherIcons.CloudOff, contentDescription = null, tint = t.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            FreshnessCopy.NOT_DOWNLOADED,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.body,
            fontSize = 13.6.sp,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
