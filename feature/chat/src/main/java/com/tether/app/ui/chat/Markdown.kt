package com.tether.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.TetherExpandableBlock
import com.tether.app.ui.components.expandPeek
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.components.currentLayoutClass
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import com.tether.app.ui.theme.TetherTypography
import kotlinx.coroutines.delay
import kotlin.math.max
import com.tether.app.ui.text.SafeText
import com.tether.app.ui.text.codeText
import com.tether.app.ui.text.tokenStyle
import com.tether.app.ui.text.codeDirection
import com.tether.app.ui.text.proseDirection
import com.tether.app.ui.text.appendSafe
import com.tether.app.ui.text.appendStyled
import com.tether.app.ui.text.LocalCopyNotices
import com.tether.app.ui.text.ProsePlan
import com.tether.app.ui.text.copyExact

/**
 * T6.1: renders the [parseMarkdown] AST the way the web paints `components/markdown.tsx` with the
 * `.md-*` rules of app/globals.css (4911-5047) — `md-body` inside a bubble or a thinking body.
 *
 * Box model: every block carries its CSS margins in em of its own font; adjacent vertical margins
 * collapse (the larger wins), `.md-body > :first-child` has no top margin and `:last-child` no
 * bottom margin — the same arithmetic the browser does. A list's first/last `li` margin collapses
 * through the `ol` (it has no padding or border), so a list's outer margins are max(li, ol).
 */

/** Tag for the inline-code ranges whose rounded `--tint-md` background [MdText] paints. */
private const val CODE_TAG = "md-code"

/** ta-9jnm: the tag of a file mention's link (the resolved absolute path follows it). */
internal const val FILE_LINK_TAG = "file:"

/** ta-9jnm: how a run of prose draws its file mentions: the host's opener and a budget the run shares. */
internal class FileLinkDraw(val host: WorkspaceFileLinks, val anchor: FileMentionAnchor? = null) {
    private var left = FileLinks.MAX_LINKS

    init {
        built.incrementAndGet()
    }

    /** A tap on a drawn mention: a relative one looks at the session's touched files first (ta-8hcc), else its path opens. */
    fun open(range: FileLinkRange) {
        val mention = range.mention
        if (mention != null) host.openMention(mention, range.path, anchor) else host.open(range.path)
    }

    internal companion object {
        /** Test seam (ta-8hcc): how many runs of prose were built with file links; a tool call or a delta must not add to it. */
        val built = java.util.concurrent.atomic.AtomicInteger()
    }

    /** The mentions in [text] ([source]); each one drawn spends from the budget. */
    fun detect(text: String, source: FileLinkSource): List<FileLinkRange> {
        val found = FileLinks.detect(text, source, host.cwd, left)
        left -= found.size
        return found
    }
}

/** ta-9jnm: set by [MarkdownBody] for the prose that links its file mentions (null: nothing links). */
internal val LocalFileLinkDraw = androidx.compose.runtime.staticCompositionLocalOf<WorkspaceFileLinks?> { null }

/**
 * T5.3: tags for the in-chat find marks (`<mark className="find-mark">`, `find-mark--active`),
 * painted by [MdText] as `--find-match-bg` / `--find-match-active-bg` rounded 2px boxes behind
 * `--find-match-ink` text (globals.css 5747-5754).
 */
internal const val FIND_TAG = "find-mark"
internal const val FIND_ACTIVE = "active"
private const val FIND_PLAIN = "mark"

/** Manrope hhea metrics (ascender 1066, descender 300 per 1000): a mark's inline box. */
private const val UI_ASCENT = 1.066f
private const val UI_DESCENT = 0.30f

/**
 * T5.3: the running occurrence counter of one text run (the web's `FindHighlighter`): [next] is
 * the ordinal of the next mark, so a run that starts mid-message starts at its block's base.
 */
internal class FindCursor(val needle: String, val active: Int, start: Int) {
    var next: Int = start
}

/**
 * Where the transcript wants the active mark's bounds (root coordinates), to centre it
 * (`scrollIntoView({ block: "center" })`). Null outside a transcript.
 */
internal val LocalFindActiveMark = androidx.compose.runtime.staticCompositionLocalOf<((androidx.compose.ui.geometry.Rect) -> Unit)?> { null }

/**
 * Append [text] with every occurrence of the cursor's needle marked (markdown.tsx `emit`).
 * ta-blf: drawn by [rule] ([SafeText]; prose takes its isolates and marks from [plan]). The text
 * is encoded ONCE, whole (a flag or a CRLF a match splits stays whole), and the marks are found in
 * the ORIGINAL text, so the find's counts never depend on what is escaped; each mark then covers
 * the drawn units of its characters (a token is marked whole).
 */
internal fun AnnotatedString.Builder.appendMarked(
    text: String,
    cursor: FindCursor?,
    t: TetherTokens,
    rule: SafeText.Rule = SafeText.Rule.Prose,
    plan: ProsePlan? = null,
    files: List<FileLinkRange> = emptyList(),
    openFile: (FileLinkRange) -> Unit = {},
) {
    val token = tokenStyle(t)
    val ranges = if (cursor == null) emptyList() else findRanges(text, cursor.needle)
    if (ranges.isEmpty() && files.isEmpty()) {
        appendSafe(text, rule, token, plan)
        return
    }
    val encoded = SafeText.encodeMapped(text, rule, plan)
    val base = length
    appendStyled(encoded.display, token)
    // ta-9jnm: a file mention is the same text with a link laid over it (never a changed character);
    // its ink goes on first, so a find mark inside it still paints its own ink.
    for (file in files) {
        val start = base + encoded.displayStart(file.start)
        val end = base + encoded.displayEnd(file.end)
        addStyle(SpanStyle(color = t.violet, textDecoration = TextDecoration.Underline), start, end)
        addLink(
            LinkAnnotation.Clickable(tag = FILE_LINK_TAG + file.path, linkInteractionListener = { openFile(file) }),
            start,
            end,
        )
    }
    for (range in ranges) {
        if (cursor == null) break
        val ordinal = cursor.next++
        val start = base + encoded.displayStart(range.first)
        val end = base + encoded.displayEnd(range.last + 1)
        addStyle(SpanStyle(color = t.css.findMatchInk), start, end)
        addStringAnnotation(FIND_TAG, if (ordinal == cursor.active) FIND_ACTIVE else FIND_PLAIN, start, end)
    }
}

/** [text] as a plain prose run with its marks (a user bubble, a streaming reply): `HighlightedText`. */
internal fun markedPlain(text: String, marks: FindMarks, t: TetherTokens): AnnotatedString =
    buildAnnotatedString { appendMarked(text, FindCursor(marks.needle, marks.active, 0), t) }

/** `.md-code { padding: 0.05rem 0.35rem }`. */
private const val CODE_PAD_X_REM = 0.35f
private const val CODE_PAD_Y_REM = 0.05f

/** JetBrains Mono's advance is exactly 0.6em, so a NBSP at this size is 0.35rem wide. */
private val CODE_PAD_FONT_SIZE = (CODE_PAD_X_REM * TetherTypography.SP_PER_REM / 0.6f).sp

/** JetBrains Mono hhea metrics (ascender 1020, descender 300 per 1000): the code span's box. */
private const val MONO_ASCENT = 1.02f
private const val MONO_DESCENT = 0.30f

/** CSS `font-weight: bolder` (CSS Fonts 4 §2.2 table). */
internal fun bolder(weight: Int): Int = when {
    weight < 350 -> 400
    weight < 550 -> 700
    else -> 900
}

/** `copied` resets after this long (markdown.tsx:54). */
internal const val COPIED_RESET_MS = 1500L

/**
 * [nodes] as an AnnotatedString: `<strong>` = bolder, `<em>` = (synthesised) italic, `.md-code`
 * mono 0.85em with a NBSP pad each side (its background is painted by [MdText]), links violet +
 * underlined and opened through [onLink] (ta-coik.8: at once, [openChatLink]; a Custom Tab, never a WebView).
 * ta-blf r2: the nodes are ONE line, so one [ProsePlan] over all of them decides its isolates and
 * marks (a word split across emphasis or a link is still one line to the bidi algorithm).
 */
internal fun inlineAnnotated(
    nodes: List<MdInline>,
    t: TetherTokens,
    type: TetherTypography,
    baseWeight: Int,
    onLink: (MdInline.Link) -> Unit,
    cursor: FindCursor? = null,
    files: FileLinkDraw? = null,
): AnnotatedString = buildAnnotatedString { appendInline(nodes, t, type, baseWeight, onLink, cursor, linePlan(nodes), files) }

/** The line's pieces in reading order (inline code drawn by the code rule), for its [ProsePlan]. */
internal fun linePlan(nodes: List<MdInline>): ProsePlan {
    val segments = ArrayList<ProsePlan.Segment>()
    fun walk(list: List<MdInline>) {
        for (node in list) when (node) {
            is MdInline.Text -> segments.add(ProsePlan.Segment(node.text))
            is MdInline.Code -> segments.add(ProsePlan.Segment(node.text, code = true))
            is MdInline.Link -> walk(node.children)
            is MdInline.FileLink -> walk(node.children)
            is MdInline.Span -> walk(node.children)
            is MdInline.Strong -> walk(node.children)
            is MdInline.Em -> walk(node.children)
            is MdInline.Image -> Unit // drawn as a picture ([MdInlines] cuts it out), not as text
        }
    }
    walk(nodes)
    return ProsePlan.of(segments)
}

private fun AnnotatedString.Builder.appendInline(
    nodes: List<MdInline>,
    t: TetherTokens,
    type: TetherTypography,
    weight: Int,
    onLink: (MdInline.Link) -> Unit,
    cursor: FindCursor?,
    plan: ProsePlan,
    files: FileLinkDraw? = null,
) {
    val openFile: (FileLinkRange) -> Unit = { range -> files?.open(range) }
    for (node in nodes) {
        when (node) {
            is MdInline.Text -> appendMarked(
                node.text, cursor, t, plan = plan,
                files = files?.detect(node.text, FileLinkSource.Text).orEmpty(), openFile = openFile,
            )
            is MdInline.Code -> {
                val start = length
                withStyle(SpanStyle(fontFamily = type.mono, fontSize = CODE_PAD_FONT_SIZE)) { append(' ') }
                withStyle(type.codeInline) {
                    appendMarked(
                        node.text, cursor, t, SafeText.Rule.Code,
                        files = files?.detect(node.text, FileLinkSource.Code).orEmpty(), openFile = openFile,
                    )
                }
                withStyle(SpanStyle(fontFamily = type.mono, fontSize = CODE_PAD_FONT_SIZE)) { append(' ') }
                addStringAnnotation(CODE_TAG, node.text, start, length)
            }
            is MdInline.Link -> withLink(
                LinkAnnotation.Clickable(
                    tag = node.href,
                    styles = TextLinkStyles(SpanStyle(color = t.violet, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink(node) },
                ),
            ) { appendInline(node.children, t, type, weight, onLink, cursor, plan) }
            // ta-9jnm: a markdown link to a path is a link only where the host can open it, else its label.
            is MdInline.FileLink -> if (files == null) {
                appendInline(node.children, t, type, weight, onLink, cursor, plan)
            } else {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = FILE_LINK_TAG + node.path,
                        styles = TextLinkStyles(SpanStyle(color = t.violet, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { files?.host?.open?.invoke(node.path) },
                    ),
                ) { appendInline(node.children, t, type, weight, onLink, cursor, plan) }
            }
            is MdInline.Span -> appendInline(node.children, t, type, weight, onLink, cursor, plan, files)
            is MdInline.Strong -> {
                val w = bolder(weight)
                withStyle(SpanStyle(fontWeight = FontWeight(w))) { appendInline(node.children, t, type, w, onLink, cursor, plan, files) }
            }
            is MdInline.Em -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(node.children, t, type, weight, onLink, cursor, plan, files)
            }
            // A picture is cut out before text is built ([MdInlines]); a caller that did not gets its alt text.
            is MdInline.Image -> appendMarked(node.alt, null, t, plan = plan)
        }
    }
}

/** A text run that paints `.md-code` backgrounds (rounded `--tint-md`, 0.05rem × 0.35rem pad). */
@Composable
internal fun MdText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val codeRanges = remember(text) { text.getStringAnnotations(CODE_TAG, 0, text.length) }
    val findRanges = remember(text) { text.getStringAnnotations(FIND_TAG, 0, text.length) }
    val marks = if (findRanges.isEmpty()) Modifier else findMarkModifier(findRanges, style, { layout })
    val paint = if (codeRanges.isEmpty()) {
        Modifier
    } else {
        Modifier.drawBehind {
            val r = layout ?: return@drawBehind
            val codePx = with(density) { (style.fontSize.value * 0.85f).sp.toPx() }
            val padY = with(density) { (CODE_PAD_Y_REM * TetherTypography.SP_PER_REM).sp.toPx() }
            val radius = CornerRadius(t.radiusSm.toPx())
            for (range in codeRanges) {
                val first = r.getLineForOffset(range.start)
                val last = r.getLineForOffset(max(range.start, range.end - 1))
                for (line in first..last) {
                    val s = max(range.start, r.getLineStart(line))
                    val e = minOf(range.end, r.getLineEnd(line, visibleEnd = true))
                    if (e <= s) continue
                    val left = r.getBoundingBox(s).left
                    val right = r.getBoundingBox(e - 1).right
                    val baseline = r.getLineBaseline(line)
                    drawRoundRect(
                        color = t.tintMd,
                        topLeft = Offset(left, baseline - codePx * MONO_ASCENT - padY),
                        size = Size(right - left, codePx * (MONO_ASCENT + MONO_DESCENT) + 2 * padY),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
    Text(
        text,
        // r4: prose lays out in its content's direction; a caller that set one (a fence: LTR) keeps it.
        style = if (style.textDirection == androidx.compose.ui.text.style.TextDirection.Unspecified) style.copy(textDirection = proseDirection) else style,
        color = color,
        textAlign = textAlign ?: TextAlign.Unspecified,
        softWrap = softWrap,
        onTextLayout = { layout = it },
        modifier = modifier.then(paint).then(marks),
    )
}

/**
 * T5.3: paint the find marks (after the code-span backgrounds, so a mark inside a code span shows)
 * and report the active one's bounds to the transcript ([LocalFindActiveMark]).
 */
@Composable
private fun findMarkModifier(
    ranges: List<AnnotatedString.Range<String>>,
    style: TextStyle,
    layout: () -> TextLayoutResult?,
): Modifier {
    val t = LocalTetherTokens.current
    val density = LocalDensity.current
    val report = LocalFindActiveMark.current
    val active = ranges.firstOrNull { it.item == FIND_ACTIVE }
    val draw = Modifier.drawBehind {
        val r = layout() ?: return@drawBehind
        val px = with(density) { style.fontSize.toPx() }
        val radius = CornerRadius(2.dp.toPx())
        for (range in ranges) {
            val color = if (range.item == FIND_ACTIVE) t.css.findMatchActiveBg else t.css.findMatchBg
            forEachLineBox(r, range.start, range.end) { left, right, baseline ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, baseline - px * UI_ASCENT),
                    size = Size(right - left, px * (UI_ASCENT + UI_DESCENT)),
                    cornerRadius = radius,
                )
            }
        }
    }
    if (active == null || report == null) return draw
    val coordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    fun tryReport() {
        val coords = coordinates[0]?.takeIf { it.isAttached } ?: return
        val r = layout() ?: return
        if (active.start >= r.layoutInput.text.length) return
        val box = r.getBoundingBox(active.start)
        val end = r.getBoundingBox(maxOf(active.start, active.end - 1))
        val topLeft = coords.localToRoot(Offset(box.left, box.top))
        report(androidx.compose.ui.geometry.Rect(topLeft, Size(maxOf(1f, end.right - box.left), end.bottom - box.top)))
    }
    // A mark that became active in text already on screen reports after the next layout.
    LaunchedEffect(active.start, active.end) {
        androidx.compose.runtime.withFrameNanos { }
        tryReport()
    }
    return draw.then(
        Modifier.onGloballyPositioned { coords ->
            coordinates[0] = coords
            tryReport()
        },
    )
}

/** Each line's slice of [start, end): its left and right edge and baseline (`box-decoration-break: clone`). */
private inline fun forEachLineBox(r: TextLayoutResult, start: Int, end: Int, block: (Float, Float, Float) -> Unit) {
    if (end <= start || start >= r.layoutInput.text.length) return
    val first = r.getLineForOffset(start)
    val last = r.getLineForOffset(max(start, end - 1))
    for (line in first..last) {
        val s = max(start, r.getLineStart(line))
        val e = minOf(end, r.getLineEnd(line, visibleEnd = true))
        if (e <= s) continue
        block(r.getBoundingBox(s).left, r.getBoundingBox(e - 1).right, r.getLineBaseline(line))
    }
}

/** One block's collapsed-margin contribution, in dp. */
private class Margins(val top: Dp, val bottom: Dp)

/**
 * The web's `.md-body`: [blocks] with [style] as the inherited font (a bubble's `chatBody`, a
 * thinking body's 0.82rem/1.6) and [color] as the inherited ink.
 */
@Composable
fun MarkdownBody(
    blocks: List<MdBlock>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** T5.3: the in-chat find's marks for this message (null: none — the pre-T5.3 paths). */
    find: FindMarks? = null,
    /**
     * ta-9jnm: link the file paths this prose names (agent and sub-agent messages). Drawn only when the
     * host also provides [LocalWorkspaceFileOpener]; otherwise the prose is plain text, never a dead link.
     */
    fileLinks: Boolean = false,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val fileHost = if (fileLinks) LocalWorkspaceFileOpener.current else null
    val opener = LocalLinkOpener.current
    // ta-coik.8: a tap opens the link at once, like the web's `<a target="_blank">` (no sheet).
    // The drawn paragraphs are remembered without the handler in their keys, so the handler reads
    // the CURRENT opener and context.
    val currentOpener by rememberUpdatedState(opener)
    val currentContext by rememberUpdatedState(context)
    val currentToolbar by rememberUpdatedState(t.graphite)
    val onLink: (MdInline.Link) -> Unit = remember { { link -> openChatLink(currentContext, currentOpener, link.href, currentToolbar) } }
    fun em(size: TextUnit, factor: Float): Dp = with(density) { (size.value * factor).sp.toDp() }
    val body = style.fontSize
    val baseWeight = style.fontWeight?.weight ?: 400
    // Each block's first ordinal: the marks every earlier block paints (document order).
    val starts = remember(blocks, find?.needle) {
        find?.let { f -> blocks.runningFold(0) { acc, b -> acc + countBlockMatches(b, f.needle) } }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalFileLinkDraw provides fileHost) {
    Column(modifier) {
        var previousBottom: Dp? = null
        blocks.forEachIndexed { index, block ->
            val headingStyle = (block as? MdBlock.Heading)?.let { headingStyle(type, it.tag) }
            val m = when (block) {
                is MdBlock.Paragraph -> Margins(0.dp, em(body, 0.5f))
                is MdBlock.Heading -> Margins(em(headingStyle!!.fontSize, 0.6f), em(headingStyle.fontSize, 0.35f))
                is MdBlock.OrderedList, is MdBlock.BulletList -> Margins(em(body, 0.15f), em(body, 0.5f))
                is MdBlock.Table, is MdBlock.Code, is MdBlock.Quote -> Margins(0.dp, em(body, 0.5f))
                MdBlock.Rule -> Margins(em(body, 0.75f), em(body, 0.75f))
            }
            // `.md-body > :first-child { margin-top: 0 }` — but a list's li margin still escapes.
            val top = when {
                index == 0 && (block is MdBlock.OrderedList || block is MdBlock.BulletList) -> em(body, 0.15f)
                index == 0 -> 0.dp
                else -> maxOf(previousBottom ?: 0.dp, m.top)
            }
            if (top > 0.dp) Spacer(Modifier.height(top))
            val mark = if (find != null && starts != null && starts[index + 1] > starts[index]) BlockMarks(find, starts[index]) else null
            when (block) {
                is MdBlock.Paragraph -> MdParagraph(block, style, color, t, type, baseWeight, onLink, mark)
                is MdBlock.Heading -> MdInlines(
                    listOf(block.inlines), headingStyle!!, color, t, type, headingStyle.fontWeight!!.weight, onLink,
                    cursorKey = mark, newCursor = { mark?.cursor() }, modifier = Modifier.fillMaxWidth(),
                )
                is MdBlock.OrderedList -> MdList(block.items, ordered = true, style, color, t, type, baseWeight, onLink, mark)
                is MdBlock.BulletList -> MdList(block.items, ordered = false, style, color, t, type, baseWeight, onLink, mark)
                is MdBlock.Quote -> MdQuote(block, style, t, type, baseWeight, onLink, mark)
                is MdBlock.Table -> MdTable(block, style, t, type, onLink, mark)
                is MdBlock.Code -> MdCodeBlock(block, mark)
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
            }
            previousBottom = m.bottom
            // `.md-body > :last-child { margin-bottom: 0 }`; a last list keeps its li's 0.15em.
            if (index == blocks.lastIndex && (block is MdBlock.OrderedList || block is MdBlock.BulletList)) {
                Spacer(Modifier.height(em(body, 0.15f)))
            }
        }
    }
    }
}

/** T5.3: one block's marks — the message's needle and active ordinal, from [base] on. */
internal data class BlockMarks(val find: FindMarks, val base: Int) {
    fun cursor(offset: Int = 0): FindCursor = FindCursor(find.needle, find.active, base + offset)
}

/** `h3.md-h` 1.05rem, `h4` 0.98rem, `h5`/`h6` 0.92rem; 680, line-height 1.3 (globals.css:4915-4918). */
private fun headingStyle(type: TetherTypography, tag: Int): TextStyle = when (tag) {
    3 -> type.markdownH3
    4 -> type.markdownH4
    else -> type.markdownH5
}

@Composable
private fun MdParagraph(
    block: MdBlock.Paragraph,
    style: TextStyle,
    color: Color,
    t: TetherTokens,
    type: TetherTypography,
    weight: Int,
    onLink: (MdInline.Link) -> Unit,
    mark: BlockMarks? = null,
) {
    MdInlines(
        block.lines, style, color, t, type, weight, onLink,
        cursorKey = mark, newCursor = { mark?.cursor() }, modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * `.md-list` (padding-left 1.35em; `li` margin 0.15em 0, collapsing between items). Chrome draws
 * an ordered marker as the text `"1. "` in the item's font, its end at the content edge, and the
 * `disc` as a filled circle a third of the font's ascent wide (Manrope ascent 1.066em → 0.355em),
 * centred ~1em left of the content edge at the x-height middle (0.36em above the baseline).
 */
@Composable
private fun MdList(
    items: List<List<MdInline>>,
    ordered: Boolean,
    style: TextStyle,
    color: Color,
    t: TetherTokens,
    type: TetherTypography,
    weight: Int,
    onLink: (MdInline.Link) -> Unit,
    mark: BlockMarks? = null,
) {
    val density = LocalDensity.current
    fun em(f: Float): Dp = with(density) { (style.fontSize.value * f).sp.toDp() }
    val itemStarts = remember(items, mark) { mark?.let { m -> items.runningFold(0) { acc, item -> acc + countInlineMatches(item, m.find.needle) } } }
    val indent = em(1.35f)
    val gap = em(0.15f)
    val disc = em(0.355f)
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { n, item ->
            if (n > 0) Spacer(Modifier.height(gap))
            Layout(
                content = {
                    if (ordered) {
                        Text("${n + 1}. ", style = style, color = color, softWrap = false)
                    } else {
                        Box(Modifier.size(disc).background(color, CircleShape))
                    }
                    MdInlines(
                        listOf(item), style, color, t, type, weight, onLink,
                        cursorKey = mark, newCursor = { itemStarts?.let { mark?.cursor(it[n]) } },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                measurePolicy = remember(ordered, indent) { ListItemPolicy(ordered, indent, em(1f), em(0.36f)) },
            )
        }
    }
}

/** One `li`: the marker outside, the content at [indent]; intrinsic widths are indent + content. */
private class ListItemPolicy(
    private val ordered: Boolean,
    private val indent: Dp,
    private val discCenterX: Dp,
    private val discRise: Dp,
) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val indentPx = indent.roundToPx()
        val maxContent = if (constraints.hasBoundedWidth) max(0, constraints.maxWidth - indentPx) else Constraints.Infinity
        val content = measurables[1].measure(Constraints(maxWidth = maxContent))
        val marker = measurables[0].measure(Constraints())
        val width = if (constraints.hasBoundedWidth) max(constraints.minWidth, minOf(constraints.maxWidth, indentPx + content.width)) else indentPx + content.width
        val baseline = content[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: content.height
        return layout(width, max(content.height, marker.height)) {
            content.place(indentPx, 0)
            if (ordered) {
                marker.place(indentPx - marker.width, 0)
            } else {
                val cx = indentPx - discCenterX.toPx()
                val cy = baseline - discRise.toPx()
                marker.place((cx - marker.width / 2f).toInt(), (cy - marker.height / 2f).toInt())
            }
        }
    }

    private fun IntrinsicMeasureScope.contentWidth(width: Int): Int =
        if (width == Constraints.Infinity) width else max(0, width - indent.roundToPx())

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        indent.roundToPx() + measurables[1].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        indent.roundToPx() + measurables[1].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[1].minIntrinsicHeight(contentWidth(width))

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables[1].maxIntrinsicHeight(contentWidth(width))
}

/** `.md-quote`: 2px `--line-strong` left rule, padding 0.1em 0 0.1em 0.85em, `--muted`; its `p` keeps 0.5em below. */
@Composable
private fun MdQuote(
    block: MdBlock.Quote,
    style: TextStyle,
    t: TetherTokens,
    type: TetherTypography,
    weight: Int,
    onLink: (MdInline.Link) -> Unit,
    mark: BlockMarks? = null,
) {
    val density = LocalDensity.current
    fun em(f: Float): Dp = with(density) { (style.fontSize.value * f).sp.toDp() }
    val rule = t.lineStrong
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 2.dp + em(0.85f), top = em(0.1f), bottom = em(0.1f) + em(0.5f)),
    ) {
        MdParagraph(block.paragraph, style, t.muted, t, type, weight, onLink, mark)
    }
}

/**
 * `.md-table-wrap > .md-table`: auto table layout (columns size to content within the bubble;
 * the wrapper scrolls sideways when even the narrowest layout is wider), line-height 1.45, cells
 * padded `space-xs space-md`; header cells `--tint-xs`, 680, nowrap over a 1px `--line-strong`
 * rule; body cells top-aligned over a 1px `--line` rule, none under the last row.
 */
@Composable
private fun MdTable(
    block: MdBlock.Table,
    style: TextStyle,
    t: TetherTokens,
    type: TetherTypography,
    onLink: (MdInline.Link) -> Unit,
    mark: BlockMarks? = null,
) {
    val cellStyle = style.copy(lineHeight = 1.45.em)
    // Header cells, then each row's cells, in document order (thead before tbody).
    val cellStarts = remember(block, mark) {
        mark?.let { m -> (block.headers + block.rows.flatten()).runningFold(0) { acc, cell -> acc + countInlineMatches(cell, m.find.needle) } }
    }
    val headStyle = cellStyle.copy(fontWeight = FontWeight(680))
    val padX = t.css.spaceMd
    val padY = t.css.spaceXs
    val lineStrong = t.lineStrong
    val line = t.line
    val headBg = t.tintXs
    fun align(n: Int): TextAlign = when (block.aligns.getOrNull(n)) {
        MdAlign.Center -> TextAlign.Center
        MdAlign.Right -> TextAlign.Right
        else -> TextAlign.Left
    }
    val baseWeight = style.fontWeight?.weight ?: 400
    val geo = remember { TableGeometry() }
    val policy = remember(block.headers.size) { TablePolicy(block.headers.size, geo) }
    Layout(
        content = {
            block.headers.forEachIndexed { n, cell ->
                MdInlines(
                    listOf(cell), headStyle, t.ink, t, type, 680, onLink,
                    cursorKey = mark, newCursor = { cellStarts?.let { mark?.cursor(it[n]) } },
                    modifier = Modifier.padding(horizontal = padX, vertical = padY),
                    textAlign = align(n),
                    softWrap = false,
                )
            }
            block.rows.forEachIndexed { r, row ->
                row.forEachIndexed { n, cell ->
                    MdInlines(
                        listOf(cell), cellStyle, t.ink, t, type, baseWeight, onLink,
                        cursorKey = mark, newCursor = { cellStarts?.let { mark?.cursor(it[block.headers.size + r * block.headers.size + n]) } },
                        modifier = Modifier.padding(horizontal = padX, vertical = padY),
                        textAlign = align(n),
                    )
                }
            }
        },
        // The wrapper's width is the table's available width; then it scrolls sideways.
        modifier = Modifier
            .layout { measurable, constraints ->
                geo.available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                val p = measurable.measure(constraints.copy(minWidth = 0))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                if (geo.heights.isEmpty()) return@drawBehind
                val w = geo.width.toFloat()
                drawRect(headBg, size = Size(w, geo.heights[0].toFloat()))
                val px = 1.dp.toPx()
                for (r in 0 until geo.heights.size - 1) {
                    val y = (geo.rowTops[r] + geo.heights[r]).toFloat()
                    drawRect(if (r == 0) lineStrong else line, topLeft = Offset(0f, y), size = Size(w, px))
                }
            },
        measurePolicy = policy,
    )
}

/** The last measured table grid: the draw pass reads it; [available] comes from the wrapper. */
private class TableGeometry {
    var available: Int = Int.MAX_VALUE
    var width: Int = 0
    var rowTops: IntArray = IntArray(0)
    var heights: IntArray = IntArray(0)
}

/** CSS auto table layout over [cols] columns (header cells are nowrap: their min is their max). */
private class TablePolicy(private val cols: Int, private val geo: TableGeometry) : MeasurePolicy {
    private fun colWidths(measurables: List<IntrinsicMeasurable>, min: Boolean): IntArray {
        val w = IntArray(cols)
        measurables.forEachIndexed { i, m ->
            val c = i % cols
            val v = if (!min || i < cols) m.maxIntrinsicWidth(Constraints.Infinity) else m.minIntrinsicWidth(Constraints.Infinity)
            w[c] = max(w[c], v)
        }
        return w
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (cols == 0) return layout(0, 0) {}
        val available = geo.available
        val maxW = colWidths(measurables, min = false)
        val minW = colWidths(measurables, min = true)
        val sumMax = maxW.sum()
        val sumMin = minW.sum()
        // Max-content if it fits; else share the slack in proportion to (max - min); never below min.
        val widths = when {
            sumMax <= available -> maxW
            sumMin >= available -> minW
            else -> IntArray(cols) { c ->
                val span = (sumMax - sumMin).coerceAtLeast(1)
                minW[c] + ((available - sumMin).toLong() * (maxW[c] - minW[c]) / span).toInt()
            }
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % cols])) }
        val rows = placeables.size / cols
        val heights = IntArray(rows) { r -> (0 until cols).maxOf { c -> placeables[r * cols + c].height } }
        val rule = 1.dp.roundToPx()
        val rowTops = IntArray(rows)
        var y = 0
        for (r in 0 until rows) {
            rowTops[r] = y
            y += heights[r] + if (r < rows - 1) rule else 0
        }
        val width = widths.sum()
        geo.width = width
        geo.rowTops = rowTops
        geo.heights = heights
        val xs = IntArray(cols)
        for (c in 1 until cols) xs[c] = xs[c - 1] + widths[c - 1]
        return layout(width, y) {
            placeables.forEachIndexed { i, p -> p.place(xs[i % cols], rowTops[i / cols]) }
        }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        colWidths(measurables, min = false).sum()

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        colWidths(measurables, min = true).sum()

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.chunked(max(1, cols)).sumOf { row -> row.maxOf { it.maxIntrinsicHeight(Constraints.Infinity) } }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        maxIntrinsicHeight(measurables, width)
}

/**
 * `CodeBlock` (markdown.tsx:39-81): the fence body in `.md-pre` (1px `--line`, `--radius-sm`,
 * `--mineral-deep`, padding `space-sm space-md`, mono 0.8rem/1.5, `white-space: pre` scrolling
 * sideways) clamped by the expandable block (9rem on a phone, 16rem wider), with a tap-to-copy
 * key riding the top-right corner. Copy shows a check ("Copied") for 1.5s. No syntax
 * highlighting: the web renders fences as plain text.
 *
 * ta-coik.64: a tap copies the EXACT body ([copyExact], as markdown.tsx's writeText(code)); a hidden
 * terminal / bidi control is drawn as a visible token but never copied as one. While clamped only a peek of the body is laid out (the T6.2 pre rule:
 * the first 64 lines, 4,096 characters), so a megabyte fence, or one full of tokens, costs its peek.
 */
@Composable
internal fun MdCodeBlock(block: MdBlock.Code, mark: BlockMarks? = null) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val context = LocalContext.current
    val phone = currentLayoutClass() == TetherLayoutClass.Phone
    val clamp = if (phone) 9.dp * 16 else t.css.chatClamp
    val shape = RoundedCornerShape(t.radiusSm)
    val notices = LocalCopyNotices.current
    var copied by remember(block.code) { mutableStateOf(false) }
    val peek = remember(block.code) { expandPeek(block.code) }
    val truncated = peek.length < block.code.length
    var opened by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_RESET_MS)
            copied = false
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .cssSurface(shape, background = t.mineralDeep, border = CssBorder(1.dp, t.line)),
        ) {
            // T5.3 markdown.tsx:290-296: the active match inside this fence lifts the clamp.
            val count = if (mark != null) countPlainMatches(block.code, mark.find.needle) else 0
            val reveal = mark != null && mark.find.active >= mark.base && mark.find.active < mark.base + count
            TetherExpandableBlock(
                clamp = clamp,
                reveal = reveal,
                forceOverflow = truncated,
                onOpenChange = { opened = it },
                hiddenRows = if (truncated) { _, _ -> 0 } else null,
            ) {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    val padding = Modifier.padding(horizontal = t.css.spaceMd + 1.dp, vertical = t.css.spaceSm + 1.dp)
                    // ta-blf: the fence shows exactly what it holds (every invisible / bidi code point a
                    // token), laid out LTR; Copy still takes the raw body.
                    val style = type.codeBlock.copy(textDirection = codeDirection)
                    if (mark == null) {
                        Text(codeText(if (opened || !truncated) block.code else peek), style = style, color = t.ink, softWrap = false, modifier = padding)
                    } else {
                        // r3: the find marks keep the peek too, unless the active match lies past it. While
                        // the active match is here the find opened the fence: that is not the reader asking
                        // for all of it, so only a reader's own open (with no active match here) lifts the peek.
                        val pastPeek = remember(block, mark, peek) {
                            reveal && truncated && findRanges(block.code, mark.find.needle).getOrNull(mark.find.active - mark.base)?.let { it.last >= peek.length } == true
                        }
                        val body = if ((opened && !reveal) || !truncated || pastPeek) block.code else peek
                        val marked = remember(body, mark, t) { buildAnnotatedString { appendMarked(body, mark.cursor(), t, SafeText.Rule.Code) } }
                        MdText(marked, style, t.ink, padding, softWrap = false)
                    }
                }
            }
        }
        CopyKey(
            copied = copied,
            onClick = { if (copyExact(context, SafeText.code(block.code), notices, raw = block.code, label = "code")) copied = true },
            modifier = Modifier.align(Alignment.TopEnd).offset(x = -t.css.spaceXs, y = t.css.spaceXs),
        )
    }
}

/**
 * `.md-copy-btn` under the material layer (`:root .md-copy-btn`, globals.css:8598): a key face
 * (1px `--key-side`, `--key-face`), `--radius-sm`, 44×44 at 0.85 opacity on touch
 * (`@media (hover: none)`); pressed turns `--key-face-deep`. The bevels, `--shadow-key` and
 * `--press-travel` were retired at tether 887c222 (transparent / 0 in Studio). Lucide Copy /
 * Check at 14px.
 */
@Composable
private fun CopyKey(copied: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTetherTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusSm)
    val label = if (copied) "Copied" else "Copy code"
    Box(
        modifier
            .alpha(0.85f)
            .size(44.dp)
            .cssSurface(
                shape,
                background = if (pressed) t.keyFaceDeep else t.keyFace,
                border = CssBorder(1.dp, t.keySide),
            )
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (copied) TetherIcons.Check else TetherIcons.Copy,
            contentDescription = null,
            tint = t.ink,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A finished message body as markdown (kept for the sub-agent panel's prompt, T6.4's surface):
 * the bubble font at [fontSize].
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val type = LocalTetherTypography.current
    val blocks = remember(text) { parseMarkdown(text) }
    val style = if (fontSize == TextUnit.Unspecified) type.chatBody else type.chatBody.copy(fontSize = fontSize)
    MarkdownBody(blocks, style, color, modifier)
}
