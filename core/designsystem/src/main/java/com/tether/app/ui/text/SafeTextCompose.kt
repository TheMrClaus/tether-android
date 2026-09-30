package com.tether.app.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalContext
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
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.PreDisplay
import com.tether.app.ui.components.TetherKey
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
 * A copy's notice: its [message], and the exact source its "Copy raw" key puts on the clipboard.
 * r4: that key is the ONLY raw copy path (the code key has no long press).
 */
class CopyNotice(val message: String, val raw: String, val serial: Long)

/** The copy notice in scope (the transcript's); a copy outside one shows none. */
@Stable
class CopyNotices {
    var current: CopyNotice? by mutableStateOf(null)
        private set
    private var serial = 0L

    /** A safe copy that showed [hidden] controls as tokens; "Copy raw" puts [raw] on the clipboard. */
    fun show(hidden: Int, raw: String) {
        current = CopyNotice(SafeText.copyNotice(hidden), raw, ++serial)
    }

    /**
     * r3: every copy, clean or not, first clears the notice: a "Copy raw" left from an earlier copy
     * must never put that earlier payload on the clipboard.
     */
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
 * Copy what [display] draws the SAFE way ([SafeText.forCopy]): the dangerous controls as their
 * visible tokens, the rest exactly. When any was shown, [notices] offers "Copy raw" ([raw], or
 * the display decoded). False when the clipboard is unavailable.
 */
fun copySafely(context: Context, display: String, notices: CopyNotices?, raw: String? = null, label: String = "text"): Boolean {
    notices?.clear()
    val copied = SafeText.forCopy(display)
    val ok = putOnClipboard(context, copied.text, label)
    if (ok && copied.hidden > 0) notices?.show(copied.hidden, raw ?: SafeText.original(display))
    return ok
}

/**
 * T6.7 + ta-blf: the clipboard a transcript row's selection copies through: the text the
 * selection copied is what the row DRAWS; this puts [SafeText.forCopy] of it on the system
 * clipboard (tokens decoded, dangerous controls kept visible, break opportunities dropped) and,
 * when it kept any, tells [notices] (which offers "Copy raw"). A copy with nothing to change
 * passes through untouched, rich text and all.
 */
class SafeCopyClipboard(private val delegate: Clipboard, private val notices: CopyNotices?) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        notices?.clear() // r3: never leave an earlier copy's "Copy raw" behind
        if (clipEntry == null) return delegate.setClipEntry(null)
        val data = clipEntry.clipData
        val text = if (data.itemCount > 0) data.getItemAt(0).text else null
        if (text == null) return delegate.setClipEntry(clipEntry)
        val copied = SafeText.forCopy(text)
        if (copied.text == text.toString() && text.indexOf(SafeText.MARK) < 0) return delegate.setClipEntry(clipEntry)
        delegate.setClipEntry(ClipEntry(ClipData.newPlainText(data.description?.label ?: "text", copied.text)))
        if (copied.hidden > 0) notices?.show(copied.hidden, SafeText.original(text))
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
 * The copy notice: "N hidden control characters copied as ⟨U+…⟩" and a "Copy raw" key that puts
 * the exact source on the clipboard. Polite live region: TalkBack reads it once.
 */
@Composable
fun CopyNoticeHost(notices: CopyNotices, modifier: Modifier = Modifier) {
    val notice = notices.current ?: return
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val context = LocalContext.current
    LaunchedEffect(notice.serial) {
        delay(COPY_NOTICE_MS)
        notices.dismiss(notice)
    }
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier
            .widthIn(max = 480.dp)
            .cssSurface(shape, background = t.graphiteRaised, border = CssBorder(1.dp, t.line))
            .padding(start = t.css.spaceMd, end = t.css.spaceXs)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .testTag(COPY_NOTICE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceSm),
    ) {
        Text(notice.message, style = type.body, color = t.ink, modifier = Modifier.weight(1f, fill = false))
        val raw = notice.raw
        TetherKey(
            onClick = {
                putOnClipboard(context, raw)
                notices.dismiss(notice)
            },
            classes = KeyClasses.ButtonSecondary,
            label = "Copy raw",
            contentDescription = "Copy raw, with the hidden control characters",
            modifier = Modifier.testTag(COPY_RAW_TAG),
        )
    }
}

const val COPY_NOTICE_TAG = "copy-notice"
const val COPY_RAW_TAG = "copy-raw"
