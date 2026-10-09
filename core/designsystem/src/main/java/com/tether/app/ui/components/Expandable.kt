package com.tether.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Clamp-and-expand for long payloads (components/expandable-block.tsx + globals.css 5282-5330).
 *
 * The transcript is the ONLY vertical scroller: a long block is clamped (`max-height:
 * var(--chat-clamp, 16rem)`, overflow hidden) to a peek with a toggle that lays the whole thing
 * out inline. Rule for anything added to the transcript: never give a block its own vertical
 * scroller; horizontal-only ([scrollX], a wide diff row) is fine.
 *
 * - The toggle only appears when the content overflows the clamp by more than
 *   [ExpandOverflowSlop] (8px of rounding is not "more"), or while the block is open.
 * - Clamped with more below (`is-more`), the last 2rem fades out, so a cut line reads as
 *   "there is more"; a block that fits exactly never looks truncated.
 * - [reveal] (the in-chat find bar's active match is inside) opens the block on its rising edge
 *   and never re-collapses it.
 * - The clamp defaults to `--chat-clamp` (16rem). The web's chat view lowers it to 9rem below
 *   48rem (`.chat-view { --chat-clamp: 9rem }`, globals.css:8464); that is a chat-screen rule, so
 *   the chat screen (T6.x) passes `clamp` — the primitive does not guess its host.
 * - Collapsing removes height ABOVE the toggle; the web pins the block's bottom edge by scrolling
 *   the transcript by the delta. Here the transcript owner receives that delta through
 *   [onCollapseShift] (e.g. `listState.scrollBy(delta)`).
 */
const val ExpandOverflowSlop: Float = 8f

/** Past this many rows, estimate from the first row's height instead of counting (the web's 3000). */
const val ExpandExactCountLimit: Int = 3000

/** Width of the fade at the bottom of a clamped block (`calc(100% - 2rem)` → transparent). */
val ExpandFade: Dp = 32.dp

/**
 * How many RENDERED rows are hidden below the clamp (`hiddenRowCount`): rows whose top is at or
 * below `cut - 1` (a row straddling the cut is still half-readable, so it does not count). Tops
 * are deduped (rounded) like the web's rect walk. Above [ExpandExactCountLimit] rows it estimates
 * `max(1, round(hiddenPx / firstRowHeight))`; an unusable row height yields 0 ("Show more").
 */
fun hiddenRowCount(rowTops: List<Float>, firstRowHeight: Float, cut: Float, hiddenPx: Float): Int {
    if (rowTops.isEmpty()) return 0
    if (rowTops.size > ExpandExactCountLimit) {
        if (!(firstRowHeight > 0f)) return 0
        return max(1, (hiddenPx / firstRowHeight).roundToInt())
    }
    return rowTops.filter { it >= cut - 1f }.map { it.roundToInt() }.toSet().size
}

/** The toggle's words (`ToggleRow`): "Show less" / "Show 1,204 more lines" / "Show more". */
fun expandToggleLabel(open: Boolean, hidden: Int?, locale: Locale = Locale.getDefault()): String = when {
    open -> "Show less"
    hidden != null && hidden > 0 ->
        "Show ${NumberFormat.getIntegerInstance(locale).format(hidden)} more line${if (hidden == 1) "" else "s"}"
    else -> "Show more"
}

/** Whether a clamped block has genuinely more to show (`hiddenPx <= OVERFLOW_SLOP` → no toggle). */
fun expandOverflows(contentPx: Int, clampPx: Int, slopPx: Float): Boolean = contentPx - clampPx > slopPx

/**
 * Clamp arbitrary block content (a diff, a rendered card body) with an expand toggle.
 * [hiddenRows] counts the rendered rows below a cut (px from the content top); null prints the
 * unnumbered "Show more" (an unnumbered label beats a wrong number). [initiallyOpen] is for
 * restoring state and previews.
 */
@Composable
fun TetherExpandableBlock(
    modifier: Modifier = Modifier,
    scrollX: Boolean = false,
    reveal: Boolean = false,
    clamp: Dp = LocalTetherTokens.current.css.chatClamp,
    initiallyOpen: Boolean = false,
    hiddenRows: ((cutPx: Float, hiddenPx: Float) -> Int)? = null,
    onCollapseShift: ((deltaPx: Float) -> Unit)? = null,
    onOpenChange: ((Boolean) -> Unit)? = null,
    forceOverflow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val t = LocalTetherTokens.current
    var open by rememberSaveable { mutableStateOf(initiallyOpen || reveal) }
    var revealSeen by remember { mutableStateOf(reveal) }
    if (reveal != revealSeen) {
        revealSeen = reveal
        if (reveal) open = true
    }
    var contentPx by remember { mutableIntStateOf(0) }
    var clampPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableFloatStateOf(Float.NaN) }
    var anchor by remember { mutableStateOf<Float?>(null) }
    val slopPx = with(LocalDensity.current) { ExpandOverflowSlop.dp.toPx() }
    val overflowing = !open && (forceOverflow || expandOverflows(contentPx, clampPx, slopPx))
    androidx.compose.runtime.LaunchedEffect(open) { onOpenChange?.invoke(open) }
    val hidden: Int? = if (overflowing) hiddenRows?.invoke(clampPx.toFloat(), (contentPx - clampPx).toFloat()) else null

    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val bottom = coords.positionInWindow().y + coords.size.height
                val pinned = anchor
                if (pinned != null && !open) {
                    anchor = null
                    val delta = bottom - pinned
                    if (delta != 0f) onCollapseShift?.invoke(delta)
                }
                bottomPx = bottom
            },
    ) {
        val clip = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .then(
                if (overflowing) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fade = ExpandFade.toPx().coerceAtMost(size.height)
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black,
                                    ((size.height - fade) / size.height) to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .layout { measurable, constraints ->
                val limit = clamp.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                contentPx = placeable.height
                clampPx = limit
                val h = if (open) placeable.height else minOf(placeable.height, limit)
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
        Box(clip) {
            Box(
                if (scrollX) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
            ) {
                content()
            }
        }
        if (overflowing || open) {
            ExpandToggleRow(open = open, hidden = hidden) {
                anchor = bottomPx.takeUnless { it.isNaN() }
                open = !open
            }
        }
    }
}

/**
 * The common case: a monospace block of tool input/output (`ExpandablePre`). The hidden-row count
 * comes from the text's real line boxes (a wrapped JSON line counts as the rows it renders).
 */
@Composable
fun TetherExpandablePre(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTetherTypography.current.codeBlock,
    color: Color = LocalTetherTokens.current.ink,
    contentDescription: String? = null,
    initiallyOpen: Boolean = false,
    clamp: Dp = LocalTetherTokens.current.css.chatClamp,
    textModifier: Modifier = Modifier,
    onCollapseShift: ((deltaPx: Float) -> Unit)? = null,
    /** ta-blf: how the (peeked) text is drawn; null: as is. A stable instance (it keys a remember). */
    display: PreDisplay? = null,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Line tops are in the text's own space; the clamp cut is measured from the block top, so the
    // text's inset (e.g. a padding in [textModifier]) is subtracted from the cut.
    var outerTop by remember { mutableFloatStateOf(0f) }
    var textTop by remember { mutableFloatStateOf(0f) }
    var opened by remember { mutableStateOf(initiallyOpen) }
    // T6.2 (security review M3): while clamped, only a peek is laid out — enough to overfill any
    // clamp — so a 64K payload costs its first few K per frame, not all of it. A peek that is not
    // the whole text says "Show more" unnumbered (the web's own fallback when it cannot count).
    val peek = remember(text) { expandPeek(text) }
    val truncated = peek.length < text.length
    TetherExpandableBlock(
        modifier = modifier,
        initiallyOpen = initiallyOpen,
        clamp = clamp,
        onCollapseShift = onCollapseShift,
        onOpenChange = { opened = it },
        forceOverflow = truncated,
        hiddenRows = { cut, hiddenPx ->
            val r = layoutResult
            if (r == null || truncated) {
                0
            } else {
                val tops = List(r.lineCount) { r.getLineTop(it) }
                val first = if (r.lineCount > 0) r.getLineBottom(0) - r.getLineTop(0) else 0f
                hiddenRowCount(tops, first, cut - (textTop - outerTop), hiddenPx)
            }
        },
    ) {
        // The peek is cut from the ORIGINAL text (its bound stays in source characters), then drawn.
        val raw = if (opened || !truncated) text else peek
        val shown = remember(raw, display) { display?.show(raw) ?: AnnotatedString(raw) }
        Text(
            shown,
            style = style,
            color = color,
            onTextLayout = { layoutResult = it },
            modifier = Modifier
                .onGloballyPositioned { outerTop = it.positionInWindow().y }
                .then(textModifier)
                .onGloballyPositioned { textTop = it.positionInWindow().y }
                .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
            ),
        )
    }
}

/**
 * ta-blf: how a [TetherExpandablePre] draws its text, e.g. with invisible and bidi code points made
 * visible ([com.tether.app.ui.text.SafeText], `safePreDisplay`). Applied after the peek is cut.
 */
fun interface PreDisplay {
    fun show(text: String): AnnotatedString
}

/** How much of a clamped text is laid out: the first 64 lines, at most 4,096 characters. */
const val ExpandPeekLines: Int = 64
const val ExpandPeekChars: Int = 4096

fun expandPeek(text: String): String {
    var end = minOf(text.length, ExpandPeekChars)
    var lines = 0
    for (i in 0 until end) {
        if (text[i] == '\n' && ++lines == ExpandPeekLines) {
            end = i
            break
        }
    }
    if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end)
}

/**
 * `.chat-expand-toggle`: full width, ≥2.25rem, a `--line` top rule over `--tint-xs`, muted
 * JetBrains Mono 0.7rem/0.02em, a 13px chevron that turns 180° when open. On a phone
 * ([TetherLayoutClass.Phone], the web below 48rem) it is min 44px tall with 0.74rem text
 * (globals.css:8466). Touch: pressed is the web's hover (`--ink` on `--tint-sm`).
 */
@Composable
fun ExpandToggleRow(open: Boolean, hidden: Int?, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = if (reduced) snap<Float>() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing())
    val rotation by animateFloatAsState(if (open) 180f else 0f, spec, label = "expandChevron")
    val ink by animateColorAsState(
        if (pressed) t.ink else t.muted,
        if (reduced) snap() else tween(t.css.durationFast, easing = t.css.easeOut.toEasing()),
        label = "expandInk",
    )
    val label = expandToggleLabel(open, hidden)
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val lineColor = t.line
    val bg = if (pressed) t.tintSm else t.tintXs
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = if (phone) 44.dp else 36.dp)
            .drawWithContent {
                drawRect(bg)
                drawRect(lineColor, size = Size(size.width, 1.dp.toPx()), topLeft = Offset.Zero)
                drawContent()
            }
            .padding(start = t.css.spaceMd, end = t.css.spaceMd, top = t.css.spaceXs + 1.dp, bottom = t.css.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TetherIcons.ChevronDown, contentDescription = null, tint = ink, modifier = Modifier.size(13.dp).graphicsLayer { rotationZ = rotation })
        Text(
            label,
            color = ink,
            style = TextStyle(fontFamily = type.mono, fontSize = if (phone) 11.84.sp else 11.2.sp, letterSpacing = 0.02.em),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
