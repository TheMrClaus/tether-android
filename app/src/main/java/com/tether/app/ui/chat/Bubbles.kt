package com.tether.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.Manrope
import com.tether.app.ui.theme.TetherDimens
import com.tether.app.ui.theme.TetherWeights
import androidx.compose.ui.draw.rotate

/** USER bubble: teal-green, right-aligned, bottom-right corner tightened. */
@Composable
fun UserBubble(block: TurnBlock, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(
        topStart = TetherDimens.radiusMd,
        topEnd = TetherDimens.radiusMd,
        bottomEnd = TetherDimens.radiusSm,
        bottomStart = TetherDimens.radiusMd,
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .maxWidthFraction(TetherDimens.bubbleMaxFraction)
                .background(t.userBubbleBg, shape)
                .border(1.dp, t.userBubbleBorder, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                block.text.orEmpty(),
                color = t.userBubbleInk,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 14.4.sp,
                lineHeight = 14.4.sp * 1.55f,
            )
            block.attachments?.forEach { attachment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(t.tintMd, RoundedCornerShape(TetherDimens.radiusSm))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Lucide.Paperclip, contentDescription = null, tint = t.userBubbleInk, modifier = Modifier.size(12.dp))
                    Text(
                        attachment.name,
                        color = t.userBubbleInk,
                        fontFamily = Manrope,
                        fontSize = 12.2.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * AGENT bubble: graphite-raised, left-aligned. Streaming (done != true) renders
 * plain text with a blinking violet caret; finished text renders markdown.
 */
@Composable
fun AgentBubble(block: TurnBlock, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val shape = RoundedCornerShape(
        topStart = TetherDimens.radiusMd,
        topEnd = TetherDimens.radiusMd,
        bottomEnd = TetherDimens.radiusMd,
        bottomStart = TetherDimens.radiusSm,
    )
    val streaming = block.done != true
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .maxWidthFraction(TetherDimens.bubbleMaxFraction)
                .background(t.graphiteRaised, shape)
                .border(1.dp, t.line, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (streaming) {
                StreamingText(block.text.orEmpty())
            } else {
                MarkdownText(text = block.text.orEmpty(), color = t.ink)
            }
            if (block.aborted == true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Lucide.CircleStop, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                    Text(
                        "interrupted",
                        color = t.faint,
                        fontFamily = Manrope,
                        fontWeight = TetherWeights.label,
                        fontSize = 10.9.sp,
                    )
                }
            }
        }
    }
}

/** Plain pre-wrap text + 8x16dp violet caret blinking in 1s steps. */
@Composable
private fun StreamingText(text: String) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val caretAlpha: Float = if (reduced) 1f else {
        val transition = rememberInfiniteTransition(label = "caret")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1000
                    1f at 0
                    1f at 499
                    0f at 500
                    0f at 999
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "caretAlpha",
        )
        value
    }
    val caretId = "caret"
    val annotated = buildAnnotatedString {
        append(text)
        appendInlineContent(caretId, "▍")
    }
    Text(
        annotated,
        color = t.ink,
        fontFamily = Manrope,
        fontWeight = TetherWeights.body,
        fontSize = 14.4.sp,
        lineHeight = 14.4.sp * 1.55f,
        inlineContent = mapOf(
            caretId to InlineTextContent(
                Placeholder(width = 10.sp, height = 16.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
            ) {
                Box(
                    Modifier
                        .size(width = 8.dp, height = 16.dp)
                        .graphicsLayer { alpha = caretAlpha }
                        .background(t.violet),
                )
            },
        ),
    )
}

/** Thinking block: collapsible, collapsed by default (visual-spec §4). */
@Composable
fun ThinkingCard(block: TurnBlock, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    var expanded by rememberSaveable(block.blockId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(TetherDimens.radiusMd)
    Column(
        modifier
            .fillMaxWidth()
            .background(t.tintXs, shape)
            .border(1.dp, t.line, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Lucide.Brain, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Text(
                "Thinking",
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.label,
                fontSize = 12.2.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = t.faint,
                modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Text(
                block.text.orEmpty(),
                color = t.muted,
                fontFamily = Manrope,
                fontWeight = TetherWeights.body,
                fontSize = 13.1.sp,
                lineHeight = 13.1.sp * 1.6f,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}
