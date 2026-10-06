package com.tether.app.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.PreDisplay
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography
import com.tether.app.ui.theme.TetherTokens
import kotlinx.coroutines.delay

/** ta-blf: a token's look: the `--warning` ink, so it never passes for the text around it. */
fun tokenStyle(t: TetherTokens): SpanStyle = SpanStyle(color = t.warning)

/** Code surfaces lay out LTR, like the web's `<pre>` / `<code>` in its LTR page. */
val codeDirection: TextDirection = TextDirection.Ltr

/**
 * r4: prose lays out in the direction of its CONTENT (its first strong character, UAX #9 P2), in
 * an LTR and in an RTL UI alike: a Latin-first line is LTR, a Hebrew-first line RTL. A Compose Text
 * with no direction would take the UI's layout direction instead, so an RTL UI would draw a Latin
 * line in an RTL paragraph, against what the text says. Only a text with no strong character at
 * all falls back to the UI's direction.
 */
val proseDirection: TextDirection = TextDirection.Content

/**
 * Append an already-drawn [display] ([SafeText]), styling its tokens: a run of adjacent tokens
 * (break opportunities between them included) is ONE span, so a flood of them stays cheap.
 */
fun AnnotatedString.Builder.appendStyled(display: String, style: SpanStyle) {
    val base = length
    append(display)
    var at = display.indexOf(SafeText.MARK)
    var spanFrom = -1
    var spanTo = -1
    while (at >= 0) {
        val u = SafeText.unitAt(display, at)
        if (u != null && !u.isBreak) {
            if (spanFrom >= 0 && at == spanTo) {
                spanTo = u.end
            } else {
                if (spanFrom >= 0) addStyle(style, base + spanFrom, base + spanTo)
                spanFrom = at
                spanTo = u.end
            }
        } else if (u != null && spanFrom >= 0 && at == spanTo) {
            spanTo = u.end // a break opportunity inside a run stays in the span
        }
        at = display.indexOf(SafeText.MARK, u?.end ?: (at + 1))
    }
    if (spanFrom >= 0) addStyle(style, base + spanFrom, base + spanTo)
}

/** Append [text] drawn by [rule] (prose with [plan]), tokens styled. */
fun AnnotatedString.Builder.appendSafe(text: String, rule: SafeText.Rule, style: SpanStyle, plan: ProsePlan? = null) =
    appendStyled(SafeText.encode(text, rule, plan), style)

/** [display] with its tokens styled. */
fun styledDisplay(display: String, style: SpanStyle): AnnotatedString {
    if (display.indexOf(SafeText.MARK) < 0) return AnnotatedString(display)
    return AnnotatedString.Builder(display.length).apply { appendStyled(display, style) }.toAnnotatedString()
}

/** [text] as prose draws it, tokens styled, laid out in its content's direction ([proseDirection]); remembered. */
@Composable
fun proseText(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) {
        AnnotatedString.Builder(text.length).apply {
            withStyle(ParagraphStyle(textDirection = proseDirection)) { appendStyled(SafeText.prose(text), tokenStyle(t)) }
        }.toAnnotatedString()
    }
}

/** [text] as code draws it; [breakAnywhere] lets it wrap between any two characters (never inside a token). */
@Composable
fun codeText(text: String, breakAnywhere: Boolean = false): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t, breakAnywhere) {
        val display = SafeText.code(text)
        styledDisplay(if (breakAnywhere) SafeText.breakAnywhere(display) else display, tokenStyle(t))
    }
}

/**
 * ta-28i: a NAME, path or id outside the transcript (a file name, a breadcrumb, a search hit's
 * path, a working directory) as a one-line surface draws it ([SafeText.Rule.Line]: code, and TAB /
 * LF / CR as tokens, r2), tokens styled, in an LTR paragraph ([codeDirection]) carried by the text
 * itself, so it lays out LTR whatever style or UI direction it is drawn in.
 */
@Composable
fun codeLabel(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) { codeLabel(text, tokenStyle(t)) }
}

/** [codeLabel] outside composition. */
fun codeLabel(text: String, style: SpanStyle): AnnotatedString =
    AnnotatedString.Builder(text.length).apply {
        withStyle(ParagraphStyle(textDirection = codeDirection)) { appendSafe(text, SafeText.Rule.Line, style) }
    }.toAnnotatedString()

/** [text] as command output draws it ([SafeText.terminal]). */
@Composable
fun terminalText(text: String): AnnotatedString {
    val t = LocalTetherTokens.current
    return remember(text, t) { styledDisplay(SafeText.terminal(text), tokenStyle(t)) }
}

/** A pre's display as code, or as command output when [terminal] (stable per skin: it keys the pre's remember). */
@Composable
fun safePreDisplay(terminal: Boolean = false): PreDisplay {
    val t = LocalTetherTokens.current
    return remember(t, terminal) {
        val style = tokenStyle(t)
        PreDisplay { text -> styledDisplay(if (terminal) SafeText.terminal(text) else SafeText.code(text), style) }
    }
}

// ---- copy -------------------------------------------------------------------------------------

/**
 * A copy's notice (information only: the clipboard already holds the exact source, nothing more
 * is needed to get it). [message] says how many hidden controls the copied text carries.
 */
class CopyNotice(val message: String, val serial: Long)

/** The copy notice in scope (the transcript's); a copy outside one shows none. */
@Stable
class CopyNotices {
    var current: CopyNotice? by mutableStateOf(null)
        private set
    private var serial = 0L

    /** A copy whose exact text carries [hidden] controls the screen draws as tokens. */
    fun show(hidden: Int) {
        current = CopyNotice(SafeText.copyNotice(hidden), ++serial)
    }

    /** Every copy, clean or not, first clears the notice: an earlier copy's notice never outlives it. */
    fun clear() {
        current = null
    }

    fun dismiss(notice: CopyNotice) {
        if (current === notice) current = null
    }
}

val LocalCopyNotices = staticCompositionLocalOf<CopyNotices?> { null }

/** Put [text] on the system clipboard; false when it is unavailable. */
fun putOnClipboard(context: Context, text: String, label: String = "text"): Boolean = runCatching {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    true
}.getOrDefault(false)

/**
 * ta-coik.64: copy the EXACT source text of what [display] draws, as the web's
 * `navigator.clipboard.writeText` does ([raw] when the caller has it, else the display decoded by
 * [SafeText.original]). The screen keeps drawing hidden controls as visible tokens; the clipboard
 * never carries one. When the text carries any, [notices] says so (information, not a step). False
 * when the clipboard is unavailable. [strict] only widens what the notice counts (one-line names).
 */
fun copyExact(context: Context, display: String, notices: CopyNotices?, raw: String? = null, label: String = "text", strict: Boolean = false): Boolean {
    notices?.clear()
    val ok = putOnClipboard(context, raw ?: SafeText.original(display), label)
    if (ok) {
        val hidden = SafeText.forCopy(display, strict).hidden
        if (hidden > 0) notices?.show(hidden)
    }
    return ok
}

/**
 * T6.7 + ta-coik.64: the clipboard a transcript row's selection copies through: the text the
 * selection copied is what the row DRAWS (hidden controls as visible tokens, break opportunities
 * between); this puts the EXACT source on the system clipboard ([SafeText.original]: tokens
 * decoded to the characters they stand for, break opportunities dropped), as the browser copies
 * the page's own text, and, when the text carries hidden controls, tells [notices] (information
 * only). A copy with nothing drawn in it passes through untouched, rich text and all.
 */
class SafeCopyClipboard(private val delegate: Clipboard, private val notices: CopyNotices?) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        notices?.clear() // never leave an earlier copy's notice behind
        if (clipEntry == null) return delegate.setClipEntry(null)
        val data = clipEntry.clipData
        val text = if (data.itemCount > 0) data.getItemAt(0).text else null
        if (text == null) return delegate.setClipEntry(clipEntry)
        val hidden = SafeText.forCopy(text).hidden
        val exact = SafeText.original(text)
        if (exact == text.toString() && text.indexOf(SafeText.MARK) < 0) {
            delegate.setClipEntry(clipEntry)
        } else {
            delegate.setClipEntry(ClipEntry(ClipData.newPlainText(data.description?.label ?: "text", exact)))
        }
        if (hidden > 0) notices?.show(hidden)
    }

    override val nativeClipboard get() = delegate.nativeClipboard
}

private fun CharSequence.indexOf(c: Char): Int {
    for (i in indices) if (this[i] == c) return i
    return -1
}

/** How long the copy notice stays (it also goes with the next copy). */
const val COPY_NOTICE_MS: Long = 8_000L

/**
 * The copy notice: "N hidden control characters in the copied text, drawn as ⟨U+…⟩" (information
 * only: the clipboard holds the exact text already). Polite live region: TalkBack reads it once.
 */
@Composable
fun CopyNoticeHost(notices: CopyNotices, modifier: Modifier = Modifier) {
    val notice = notices.current ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    LaunchedEffect(notice.serial) {
        delay(COPY_NOTICE_MS)
        notices.dismiss(notice)
    }
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier
            .widthIn(max = 480.dp)
            .cssSurface(shape, background = t.graphiteRaised, border = CssBorder(1.dp, t.line))
            .padding(horizontal = t.css.spaceMd, vertical = t.css.spaceSm)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .testTag(COPY_NOTICE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.message, style = type.body, color = t.ink)
    }
}

const val COPY_NOTICE_TAG = "copy-notice"
