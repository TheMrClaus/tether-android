package com.tether.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.SpinningIcon
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.text.ProsePlan
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography

/*
 * ta-coik.58 (#242): `![alt](src)` in a chat message draws an inline picture, the web's
 * `MarkdownImage` (components/markdown.tsx 29537e0 :89-106, `.md-img-link` / `.md-img` /
 * `.md-img-missing` in app/globals.css :4410-4433). The picture comes over [ToolMediaRepository]
 * (the paired credential for a served file or tool-media URL, none for an http(s) one), under the
 * transcript's own size caps and no-redirect rules. A tap opens it in the viewer the tool-result
 * pictures use; a load failure degrades to "alt (image unavailable)".
 */

/** The web's failure copy: `{alt} (image unavailable)`. */
internal fun imageUnavailableText(alt: String): String = "$alt (image unavailable)"

/** One drawn piece of a paragraph, in reading order. */
internal sealed interface ProsePiece {
    /** Text lines (joined with a line break), inline nodes only: no [MdInline.Image] left in them. */
    data class Text(val lines: List<List<MdInline>>) : ProsePiece

    /** Pictures that sit side by side (`inline-block`, separated by blanks on one line). */
    data class Images(val images: List<MdInline.Image>) : ProsePiece
}

internal fun hasImage(nodes: List<MdInline>): Boolean = nodes.any { node ->
    when (node) {
        is MdInline.Image -> true
        is MdInline.Link -> hasImage(node.children)
        is MdInline.Span -> hasImage(node.children)
        is MdInline.Strong -> hasImage(node.children)
        is MdInline.Em -> hasImage(node.children)
        is MdInline.Text, is MdInline.Code -> false
    }
}

private sealed interface Seg {
    class Run(val nodes: List<MdInline>) : Seg
    class Img(val image: MdInline.Image) : Seg
}

/** [nodes] cut at every picture, however deep: a styled or linked run keeps its style on both sides. */
private fun cut(nodes: List<MdInline>): List<Seg> {
    val out = ArrayList<Seg>()
    var run = ArrayList<MdInline>()
    fun flush() {
        if (run.isNotEmpty()) out.add(Seg.Run(run))
        run = ArrayList()
    }
    for (node in nodes) {
        if (node is MdInline.Image) {
            flush()
            out.add(Seg.Img(node))
        } else if (hasImage(listOf(node))) {
            val children = when (node) {
                is MdInline.Link -> node.children
                is MdInline.Span -> node.children
                is MdInline.Strong -> node.children
                is MdInline.Em -> node.children
                else -> emptyList()
            }
            for (seg in cut(children)) {
                when (seg) {
                    is Seg.Img -> {
                        flush()
                        out.add(seg)
                    }
                    is Seg.Run -> run.add(
                        when (node) {
                            is MdInline.Link -> node.copy(children = seg.nodes)
                            is MdInline.Span -> node.copy(children = seg.nodes)
                            is MdInline.Strong -> node.copy(children = seg.nodes)
                            is MdInline.Em -> node.copy(children = seg.nodes)
                            else -> node
                        },
                    )
                }
            }
        } else {
            run.add(node)
        }
    }
    flush()
    return out
}

private fun blank(nodes: List<MdInline>): Boolean = nodes.all { it is MdInline.Text && it.text.isBlank() }

private fun withChildren(node: MdInline, children: List<MdInline>): MdInline = when (node) {
    is MdInline.Link -> node.copy(children = children)
    is MdInline.Span -> node.copy(children = children)
    is MdInline.Strong -> node.copy(children = children)
    is MdInline.Em -> node.copy(children = children)
    else -> node
}

private fun childrenOf(node: MdInline): List<MdInline>? = when (node) {
    is MdInline.Link -> node.children
    is MdInline.Span -> node.children
    is MdInline.Strong -> node.children
    is MdInline.Em -> node.children
    else -> null
}

/** [nodes] without the whitespace at their start (through styled and linked runs); emptied nodes go. */
internal fun trimStartNodes(nodes: List<MdInline>): List<MdInline> {
    for ((i, node) in nodes.withIndex()) {
        val kids = childrenOf(node)
        val head: MdInline? = when {
            node is MdInline.Text -> node.text.trimStart().takeIf { it.isNotEmpty() }?.let { node.copy(text = it) }
            kids != null -> trimStartNodes(kids).takeIf { it.isNotEmpty() }?.let { withChildren(node, it) }
            else -> node
        }
        if (head != null) return listOf(head) + nodes.drop(i + 1)
    }
    return emptyList()
}

/** [nodes] without the whitespace at their end. */
internal fun trimEndNodes(nodes: List<MdInline>): List<MdInline> {
    for (i in nodes.indices.reversed()) {
        val node = nodes[i]
        val kids = childrenOf(node)
        val tail: MdInline? = when {
            node is MdInline.Text -> node.text.trimEnd().takeIf { it.isNotEmpty() }?.let { node.copy(text = it) }
            kids != null -> trimEndNodes(kids).takeIf { it.isNotEmpty() }?.let { withChildren(node, it) }
            else -> node
        }
        if (tail != null) return nodes.take(i) + tail
    }
    return emptyList()
}

/**
 * [lines] as the pieces the web's flow draws: text lines stay one block (joined by the `<br/>`),
 * pictures on one line sit side by side, and a picture on a line of its own stacks under the line
 * before it. Pure: no pictures at all gives one [ProsePiece.Text] holding every line.
 */
internal fun proseTextPieces(lines: List<List<MdInline>>): List<ProsePiece> {
    if (lines.none(::hasImage)) return listOf(ProsePiece.Text(lines))
    val out = ArrayList<ProsePiece>()
    var text = ArrayList<List<MdInline>>()
    fun flushText() {
        if (text.isNotEmpty()) out.add(ProsePiece.Text(text))
        text = ArrayList()
    }
    for (line in lines) {
        val segs = cut(line)
        var afterImage = false // the previous piece of THIS line is a picture (it ends [out])
        segs.forEachIndexed { i, seg ->
            when (seg) {
                is Seg.Run -> {
                    // Blanks beside a picture are the gap between inline-blocks, never a line of text: a
                    // run's blank edge that touches a picture is dropped (no stray " sits inline" space).
                    val nextIsImage = segs.getOrNull(i + 1) is Seg.Img
                    var nodes = seg.nodes
                    if (afterImage) nodes = trimStartNodes(nodes)
                    if (nextIsImage) nodes = trimEndNodes(nodes)
                    if (!(blank(nodes) && (afterImage || nextIsImage))) {
                        text.add(nodes)
                        afterImage = false
                    }
                }
                is Seg.Img -> {
                    val last = out.lastOrNull()
                    if (afterImage && last is ProsePiece.Images) {
                        out[out.lastIndex] = ProsePiece.Images(last.images + seg.image)
                    } else {
                        flushText()
                        out.add(ProsePiece.Images(listOf(seg.image)))
                    }
                    afterImage = true
                }
            }
        }
    }
    flushText()
    return out
}

/**
 * [lines] drawn as one block: text through [MdText] exactly as before, with every picture in them
 * drawn by [ProseImage]. With no picture this is the one [MdText] the callers always drew.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MdInlines(
    lines: List<List<MdInline>>,
    style: TextStyle,
    color: Color,
    t: TetherTokens,
    type: TetherTypography,
    weight: Int,
    onLink: (MdInline.Link) -> Unit,
    cursorKey: Any?,
    newCursor: () -> FindCursor?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    val pieces = remember(lines) { proseTextPieces(lines) }
    val texts: List<AnnotatedString?> = remember(pieces, t, type, weight, cursorKey) {
        val cursor = newCursor()
        pieces.map { piece ->
            (piece as? ProsePiece.Text)?.let { p ->
                buildAnnotatedString {
                    p.lines.forEachIndexed { i, line ->
                        if (i > 0) append('\n') // <br/>
                        append(inlineAnnotated(line, t, type, weight, onLink, cursor))
                    }
                }
            }
        }
    }
    if (pieces.size == 1 && texts[0] != null) {
        MdText(texts[0]!!, style, color, modifier, textAlign, softWrap)
        return
    }
    val density = LocalDensity.current
    val em = with(density) { style.fontSize.value.sp.toDp() }
    Column(modifier) {
        pieces.forEachIndexed { n, piece ->
            when (piece) {
                is ProsePiece.Text -> MdText(texts[n]!!, style, color, Modifier, textAlign, softWrap)
                is ProsePiece.Images -> FlowRow(
                    Modifier.padding(vertical = em * 0.25f),
                    horizontalArrangement = Arrangement.spacedBy(em * 0.5f),
                    verticalArrangement = Arrangement.spacedBy(em * 0.5f),
                ) {
                    piece.images.forEach { ProseImage(it, style, t) }
                }
            }
        }
    }
}

/** `MarkdownImage`: the picture, a spinner while it loads, or its alt text once it cannot be shown. */
@Composable
internal fun ProseImage(image: MdInline.Image, style: TextStyle, t: TetherTokens) {
    val item = remember(image.src) { ToolMediaItem(ToolMediaItem.KIND_IMAGE, "image/*", image.src) }
    var open by rememberSaveable(image.src) { mutableStateOf(false) }
    val shape = RoundedCornerShape(t.radiusMd)
    when (val state = rememberMediaImage(item, prose = true)) {
        is MediaImage.Ok -> {
            // CSS px = dp: `.md-img` shows at the SOURCE's natural size (not the sampled decode's),
            // `max-width: 100%` of the column and `max-height: 320px`, aspect kept.
            val w = state.naturalWidth.dp
            val h = state.naturalHeight.dp
            Image(
                bitmap = state.bitmap,
                contentDescription = image.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .clip(shape)
                    .clickable(role = Role.Button, onClickLabel = "View image full size") { open = true }
                    .widthIn(max = w)
                    .heightIn(max = 320.dp)
                    .aspectRatio(w.value / h.value)
                    .testTag("md-image"),
            )
            if (open) MediaLightbox(listOf(item), 0, onIndexChange = {}, onClose = { open = false })
        }
        null -> Box(
            Modifier.clip(shape).size(44.dp).background(t.tintXs).testTag("md-image-loading"),
            contentAlignment = Alignment.Center,
        ) {
            SpinningIcon(TetherIcons.Loader, tint = t.muted, size = 14.dp)
        }
        // TooLarge / Blocked / Failed: a labelled line, like the web's broken `<img>` (`.md-img-missing`).
        else -> MdText(
            remember(image.alt, t) { buildAnnotatedString { appendMarked(imageUnavailableText(image.alt), null, t, plan = ProsePlan.of(imageUnavailableText(image.alt))) } },
            style,
            t.muted,
            Modifier.testTag("md-image-missing").semantics { contentDescription = imageUnavailableText(image.alt) },
        )
    }
}
