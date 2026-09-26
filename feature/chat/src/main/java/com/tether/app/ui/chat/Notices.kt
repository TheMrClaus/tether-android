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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.TriangleAlert
import com.tether.app.protocol.model.ApiRetryState
import com.tether.app.protocol.model.TurnProjection
import com.tether.app.protocol.model.Vocab
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights

/** Outcome badge for a settled turn whose outcome != ok (visual-spec §4). */
@Composable
fun OutcomeBadge(turn: TurnProjection, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val (text, color) = when (turn.outcome) {
        Vocab.OUTCOME_CANCELLED -> "Turn interrupted" to t.muted
        Vocab.OUTCOME_ERROR -> (turn.error ?: "Turn ended with an error") to t.danger
        Vocab.OUTCOME_UNKNOWN ->
            "Outcome unknown — the turn was interrupted before it finished (it may have partially applied)" to t.warning
        else -> return
    }
    Row(
        modifier
            .background(t.tintXs, RoundedCornerShape(TetherDimens.radiusSm))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
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
    val attempts = retry.maxRetries?.let { "attempt ${retry.attempt} of $it" } ?: "attempt ${retry.attempt}"
    MarkerRow(text = "retrying ($attempts)", modifier = modifier)
}

@Composable
private fun MarkerRow(text: String, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    Row(
        modifier.alpha(0.8f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.RotateCw, contentDescription = null, tint = t.muted, modifier = Modifier.size(12.dp))
        Text(
            text,
            color = t.muted,
            fontFamily = Manrope,
            fontWeight = TetherWeights.label,
            fontSize = 11.5.sp,
        )
    }
}
