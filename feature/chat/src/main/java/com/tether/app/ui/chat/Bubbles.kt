package com.tether.app.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.protocol.model.TurnBlock
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.components.hardShadow
import com.tether.app.ui.components.maxWidthFraction
import com.tether.app.ui.components.widthMaxContent
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import com.tether.app.ui.text.proseText
import com.tether.app.ui.text.codeLabel

/**
 * The resolved `.chat-bubble` box for one skin and layout class — the cascade of globals.css
 * (4833-4851 base, 8460 phone `--chat-bubble-max: 94%`, 9052-9061 material layer, 11272-11273,
 * 11743, 11931 phone agent `max-width: 100%`) and studio.css (54, 372-375, 455-456).
 */
internal class BubbleLook(
    val maxFraction: Float,
    val padding: PaddingValues,
    val shape: RoundedCornerShape,
    val background: Color,
    val border: CssBorder?,
    val shadows: List<com.tether.app.ui.theme.CssShadow>,
    val ink: Color,
    val style: TextStyle,
)

internal fun bubbleLook(t: TetherTokens, type: TetherTypography, user: Boolean, phone: Boolean): BubbleLook {
    val css = t.css
    // Studio's chat font is 0.9rem on a phone (studio.css:455), 0.925rem wider (studio.css:372).
    val style = if (!phone) type.chatBody.copy(fontSize = (0.925f * TetherTypography.SP_PER_REM).sp) else type.chatBody
    return run {
        val padV = if (phone) 14.dp else 16.dp // 0.875rem / 1rem
        val padH = if (phone) 16.dp else 19.2.dp // 1rem / 1.2rem
        val r = 14.dp // 0.875rem
        if (user) {
            BubbleLook(
                maxFraction = if (phone) 0.94f else css.chatBubbleMax,
                padding = PaddingValues(horizontal = padH, vertical = padV),
                shape = RoundedCornerShape(topStart = r, topEnd = r, bottomEnd = 4.8.dp, bottomStart = r),
                background = t.userBubbleBg,
                border = null,
                shadows = emptyList(),
                ink = t.userBubbleInk,
                style = style,
            )
        } else {
            BubbleLook(
                maxFraction = 1f,
                // `.chat-bubble-agent { padding: 0.25rem 0 }`; the phone `.chat-bubble` padding
                // (studio.css:455) comes later, then `padding-inline: 0` (456).
                padding = PaddingValues(horizontal = 0.dp, vertical = if (phone) padV else 4.dp),
                shape = RoundedCornerShape(r),
                background = Color.Transparent,
                border = null,
                shadows = emptyList(),
                ink = t.ink,
                style = style,
            )
        }
    }
}

@Composable
internal fun BubbleBox(look: BubbleLook, alignEnd: Boolean, modifier: Modifier, shrinkToFit: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .maxWidthFraction(look.maxFraction)
                // Shrink-to-fit like the web's inline-sized bubble: as wide as its widest line
                // (a short reply is a short bubble), capped at the max; block children fill that.
                // ta-jtfq: not for a reply that is still streaming. The intrinsic-width pass lays the whole
                // growing text out once more per delta, and the text sizes itself to its content anyway, so its
                // bounds, the caret (start-aligned) and the paragraph alignment are the same without it; the
                // bubble is transparent and borderless, so nothing else shows the column's width.
                .then(if (shrinkToFit) Modifier.widthMaxContent() else Modifier)
                .cssSurface(look.shape, background = look.background, border = look.border, shadows = look.shadows)
                .padding(look.border?.width ?: 0.dp)
                .padding(look.padding),
        ) { content() }
    }
}

/** `.chat-msg-time`: block, 0.2rem above, right-aligned, 0.65rem tabular, the bubble ink at 0.55. */
@Composable
private fun MessageTime(label: String, ink: Color) {
    val type = LocalTetherTypography.current
    Spacer(Modifier.height(3.2.dp))
    Text(
        label,
        style = type.timestamp,
        color = ink,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().alpha(0.55f),
    )
}

/**
 * USER bubble (chat-view.tsx:556-606): the operator's words as plain pre-wrap text (never
 * markdown), the attachments (T7.4: pictures as thumbnails, other files as chips), and the send time. [timeLabel] is `messageClockTime(block.ts ??
 * turn.startedAt)`; "" hides it.
 */
@Composable
fun UserBubble(block: TurnBlock, modifier: Modifier = Modifier, timeLabel: String = "", find: FindMarks? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val look = bubbleLook(t, type, user = true, phone = currentLayoutClass() == TetherLayoutClass.Phone)
    BubbleBox(look, alignEnd = true, modifier) {
        val text = block.text
        if (!text.isNullOrEmpty()) {
            // T5.3: `HighlightedText` — the find marks over the plain text, when it has any.
            // ta-blf: prose ([com.tether.app.ui.text.SafeText]): an override or embedding shows as a token.
            if (find != null) MdText(remember(text, find, t) { markedPlain(text, find, t) }, look.style, look.ink) else Text(proseText(text), style = look.style, color = look.ink)
        }
        val attachments = block.attachments
        if (!attachments.isNullOrEmpty()) BubbleAttachments(attachments, look)
        if (timeLabel.isNotEmpty()) MessageTime(timeLabel, look.ink)
    }
}

/**
 * Observes the streaming agent text's intrinsic-size queries (test seam, ta-jtfq): called once for every width or
 * height the text is asked about before it is measured, each of which lays the whole text out. Null in production.
 */
internal val LocalStreamingTextObserver = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

private class IntrinsicQueryProbe(private val onQuery: () -> Unit) : androidx.compose.ui.layout.LayoutModifier {
    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: androidx.compose.ui.unit.Constraints,
    ): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicWidth(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int): Int =
        measurable.minIntrinsicWidth(height).also { onQuery() }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicWidth(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, height: Int): Int =
        measurable.maxIntrinsicWidth(height).also { onQuery() }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.minIntrinsicHeight(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int): Int =
        measurable.minIntrinsicHeight(width).also { onQuery() }

    override fun androidx.compose.ui.layout.IntrinsicMeasureScope.maxIntrinsicHeight(measurable: androidx.compose.ui.layout.IntrinsicMeasurable, width: Int): Int =
        measurable.maxIntrinsicHeight(width).also { onQuery() }
}

/**
 * AGENT bubble (chat-view.tsx:677-702). While streaming (`done !== true`) the text is plain
 * pre-wrap and a violet caret blinks on the line below it; once done the text becomes markdown and
 * the send time appears. An interrupted message says so in words. Nothing renders for a message
 * with neither text nor the interrupted mark.
 */
@Composable
fun AgentBubble(block: TurnBlock, modifier: Modifier = Modifier, timeLabel: String = "", find: FindMarks? = null) {
    val text = block.text.orEmpty()
    if (text.isEmpty() && block.aborted != true) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val look = bubbleLook(t, type, user = false, phone = currentLayoutClass() == TetherLayoutClass.Phone)
    val done = block.done == true
    val onIntrinsic = LocalStreamingTextObserver.current
    BubbleBox(look, alignEnd = false, modifier, shrinkToFit = done) {
        if (text.isNotEmpty()) {
            if (done) {
                val blocks = remember(text) { parseMarkdown(text) }
                MarkdownBody(blocks, look.style, look.ink, find = find, fileLinks = true)
            } else if (find != null) {
                MdText(remember(text, find, t) { markedPlain(text, find, t) }, look.style, look.ink)
            } else {
                Text(
                    proseText(text),
                    style = look.style,
                    color = look.ink,
                    modifier = if (onIntrinsic != null) Modifier.then(IntrinsicQueryProbe(onIntrinsic)) else Modifier,
                )
            }
        }
        if (!done) StreamingCaret(look.style)
        if (block.aborted == true) {
            Spacer(Modifier.height(t.css.spaceXs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                Icon(TetherIcons.CircleStop, contentDescription = null, tint = t.faint, modifier = Modifier.size(12.dp))
                Text(
                    "interrupted",
                    style = type.chatBody.copy(fontSize = 10.88.sp, fontWeight = FontWeight(600), letterSpacing = 0.01.em, lineHeight = 1.25.em),
                    color = t.faint,
                )
            }
        }
        val label = if (done) timeLabel else ""
        if (label.isNotEmpty()) MessageTime(label, look.ink)
    }
}

/**
 * `.chat-caret` (globals.css:5050-5059): a 0.5rem × 1rem violet block, 2px in, bottom on the
 * line's text-bottom, blinking `steps(2, start)` over 1s (on 0-50%, off after). It follows the
 * `.chat-msg-text` div, so it sits in its own line box under the text. Reduced motion: steady.
 */
@Composable
internal fun StreamingCaret(style: TextStyle) {
    val t = LocalTetherTokens.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val lineHeight: Dp = with(density) { (style.fontSize.value * (style.lineHeight.value.takeIf { it > 0f } ?: 1.2f)).sp.toDp() }
    // text-bottom = the content area's bottom: half the leading above the line box's bottom.
    val contentArea = with(density) { (style.fontSize.value * 1.366f).sp.toDp() }
    val halfLeading = ((lineHeight - contentArea) / 2).coerceAtLeast(0.dp)
    val alpha: Float = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "caret")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
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
    Box(Modifier.height(lineHeight).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        Box(
            Modifier
                .padding(start = 2.dp, bottom = halfLeading)
                .size(width = 8.dp, height = 16.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(t.violet)
                .semantics { contentDescription = "Streaming" },
        )
    }
}

/**
 * Thinking (chat-view.tsx:651-676, globals.css:6405-6451): its own row, a `<details>` collapsed by
 * default. Head: chevron (turns 90° open) + brain + "Thinking" in muted mono 0.78rem, padded
 * `space-xs space-md`. Body: `--tint-xs` box with a 1px `--line` border, `--radius-md`, muted
 * 0.82rem/1.6 markdown clamped at 13rem with the expand toggle (never a nested scroller).
 */
@Composable
fun ThinkingCard(block: TurnBlock, modifier: Modifier = Modifier) {
    val text = block.text.orEmpty()
    if (text.isEmpty()) return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    var open by rememberSaveable(block.blockId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 90f else 0f, if (reduced) snap() else tween(120), label = "thinkingChevron")
    val shape = RoundedCornerShape(t.radiusMd)
    Column(modifier.maxWidthFraction(if (phone) 1f else t.css.chatCardWidth)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Thinking"
                    stateDescription = if (open) "Expanded" else "Collapsed"
                }
                .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TetherIcons.ChevronRight, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp).rotate(rotation))
            Spacer(Modifier.width(t.css.spaceSm))
            Icon(TetherIcons.Brain, contentDescription = null, tint = t.muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(t.css.spaceSm))
            // T6.7: `.chat-thinking-head { user-select: none }`.
            androidx.compose.foundation.text.selection.DisableSelection {
                Text("Thinking", style = type.codeBlock.copy(fontSize = 12.48.sp, lineHeight = TextUnit.Unspecified), color = t.muted, modifier = Modifier.weight(1f))
            }
        }
        if (open) {
            val bodyStyle = type.chatBody.copy(fontSize = 13.12.sp, lineHeight = 1.6.em)
            val blocks = remember(text) { parseMarkdown(text) }
            Spacer(Modifier.height(t.css.spaceXs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .cssSurface(shape, background = t.tintXs, border = CssBorder(1.dp, t.line))
                    .padding(1.dp)
                    .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm),
            ) {
                TetherExpandableBlock(clamp = 208.dp) { // `.chat-thinking-body .chat-expand-clip { max-height: 13rem }`
                    MarkdownBody(blocks, bodyStyle, t.muted)
                }
            }
        }
    }
}

/**
 * T7.4 (chat-view.tsx:561-600, v112): the `.chat-bubble-attachments` row — wrapping, `space-xs`
 * apart, `space-xs` below the text. An attached picture the server materialized (`mediaRef`) is a
 * real thumbnail through the transcript's own tool-media path (the paired origin's
 * `/api/tool-media/<sha256>.<ext>` only, no redirects, bounded, the same "Image unavailable" tile
 * when it cannot load; a tap opens the viewer); every other attachment keeps its name chip, the
 * name drawn by the one-line rule (`codeLabel`: TAB / LF / CR are tokens too). A picture the
 * engine could only take as a staged path says so ("sent as a path"; with a thumbnail, the note
 * under the row).
 *
 * Divergence (fail visible): the web chips only attachments with NO `mediaRef`, so one whose ref is
 * malformed shows nothing at all; here it keeps its chip.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BubbleAttachments(attachments: List<com.tether.app.protocol.model.AttachmentMeta>, look: BubbleLook) {
    val t = LocalTetherTokens.current
    val split = remember(attachments) { splitAttachments(attachments) }
    Spacer(Modifier.height(t.css.spaceXs))
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.testTag("bubble-attachments"),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
        verticalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        if (split.media.isNotEmpty()) ToolMediaRow(split.media, bare = true)
        split.chips.forEach { attachment -> BubbleAttachmentChip(attachment, look) }
        if (split.media.isNotEmpty() && split.sentAsPath) {
            Text(
                "Sent to the agent as a file path, not an image.",
                style = look.style.copy(fontSize = 11.2.sp),
                color = t.muted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** `.chat-bubble-attachment`: a glyph (FileText for a picture, else Paperclip), the name, "sent as a path". */
@Composable
private fun BubbleAttachmentChip(attachment: com.tether.app.protocol.model.AttachmentMeta, look: BubbleLook) {
    val t = LocalTetherTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .widthIn(max = 224.dp)
            .background(t.tintLg, RoundedCornerShape(t.radiusSm))
            .padding(horizontal = 6.4.dp, vertical = 1.6.dp)
            .testTag("bubble-attachment-chip"),
    ) {
        Icon(
            if (attachment.mediaType.startsWith("image/")) TetherIcons.FileText else TetherIcons.Paperclip,
            contentDescription = null,
            tint = look.ink,
            modifier = Modifier.size(12.dp),
        )
        Text(
            codeLabel(attachment.name),
            style = look.style.copy(fontSize = 12.8.sp),
            color = t.ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (attachment.delivery == "path") {
            Text("sent as a path", style = look.style.copy(fontSize = 10.88.sp), color = t.muted, maxLines = 1)
        }
    }
}

/** What a user message's attachments draw as: thumbnails, chips, and whether one went as a path. */
@androidx.compose.runtime.Immutable
internal class BubbleAttachmentSplit(val media: List<ToolMediaItem>, val chips: List<com.tether.app.protocol.model.AttachmentMeta>, val sentAsPath: Boolean)

/**
 * chat-view.tsx:565-571: an attachment is a thumbnail when its `mediaRef` is an image `media_ref`
 * (the server materializes images only), else a chip. The ref's url is not trusted here: the loader
 * accepts only the paired origin's `/api/tool-media/` paths and shows the failure tile otherwise.
 */
internal fun splitAttachments(attachments: List<com.tether.app.protocol.model.AttachmentMeta>): BubbleAttachmentSplit {
    val media = ArrayList<ToolMediaItem>()
    val chips = ArrayList<com.tether.app.protocol.model.AttachmentMeta>()
    for (attachment in attachments) {
        val item = attachment.mediaRef?.let { ref ->
            runCatching { com.tether.app.protocol.tree.JsCodec.fromJson(ref) }.getOrNull()
        }?.let { value ->
            val record = value as? com.tether.app.protocol.tree.JsObj
            if ((record?.get("type") as? com.tether.app.protocol.tree.JsStr)?.value == "media_ref") toMediaItem(value) else null
        }?.takeIf { !it.isVideo }
        if (item != null) media += item else chips += attachment
    }
    return BubbleAttachmentSplit(media, chips, attachments.any { it.delivery == "path" })
}
