package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.ApiRetryState
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/**
 * The outcome row of a settled turn whose outcome != ok (`.chat-outcome.chat-outcome-<outcome>`,
 * chat-view.tsx:3464-3471): a cancelled turn with a `turn_interrupted` notice ([interrupt]) says who
 * interrupted it and what it stopped, behind the CircleStop glyph and `role="status"`; otherwise the
 * turn's error, else the outcome's copy. Error in `--danger`, outcome_unknown in `--warning`, a
 * cancelled turn muted; the words carry the state.
 */
@Composable
internal fun OutcomeBadge(turn: TurnProjection, modifier: Modifier = Modifier, interrupt: InterruptNoticeView? = null) {
    val t = LocalTetherTokens.current
    val outcome = turn.outcome ?: return
    if (outcome == Vocab.OUTCOME_OK) return
    val color = when (outcome) {
        Vocab.OUTCOME_ERROR -> t.danger
        Vocab.OUTCOME_UNKNOWN -> t.warning
        else -> t.muted
    }
    val interrupted = outcome == Vocab.OUTCOME_CANCELLED && interrupt != null
    val text = outcomeText(outcome, turn.error, if (interrupted) interrupt else null)
    Row(
        modifier
            .background(t.tintXs, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(if (interrupted) Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (interrupted) TetherIcons.CircleStop else TetherIcons.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(
            text,
            color = color,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 12.5.sp,
        )
    }
}

/** "continued (background task finished)" marker on continuation turns. */
@Composable
fun ContinuationMarker(modifier: Modifier = Modifier) {
    MarkerRow(text = "continued (background task finished)", modifier = modifier)
}

/** API retry marker: "retrying (attempt N of M)". */
@Composable
fun ApiRetryMarker(retry: ApiRetryState, modifier: Modifier = Modifier) {
    // chat-view.tsx:3451-3462: why, then the attempt; clears itself when real content lands.
    MarkerRow(text = apiRetryText(retry.error, retry.attempt, retry.maxRetries), modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun MarkerRow(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier.alpha(0.8f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(TetherIcons.RotateCw, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
        Text(
            text,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 11.5.sp,
        )
    }
}
