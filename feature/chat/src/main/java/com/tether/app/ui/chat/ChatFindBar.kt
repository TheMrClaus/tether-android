package com.tether.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tether.app.ui.components.CssBorder
import com.tether.app.ui.components.cssSurface
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.LocalTetherTypography

object ChatFindTags {
    const val Bar = "chat-find-bar"
    const val Input = "chat-find-input"
    const val Count = "chat-find-count"
    const val Previous = "chat-find-previous"
    const val Next = "chat-find-next"
    const val Close = "chat-find-close"
}

/**
 * T5.3: the find bar's state for one conversation (chat-view.tsx:2023-2025 + the applied request
 * nonce, 2109). Saveable, so a rotation keeps the bar, its query and the active match; a new
 * session starts closed (the web keys ChatView by session id).
 */
@Stable
internal class ChatFindState(open: Boolean, query: TextFieldValue, index: Int, appliedNonce: Long) {
    var open by mutableStateOf(open)
    var query by mutableStateOf(query)
    var index by mutableIntStateOf(index)
    var appliedNonce by mutableLongStateOf(appliedNonce)

    /** chat-view.tsx:2080-2083. */
    fun close() {
        open = false
        index = 0
    }

    /** Ctrl/Cmd+F (chat-view.tsx:2092-2098): open, or refocus, with the query selected. */
    fun openSelected() {
        open = true
        query = query.copy(selection = TextRange(0, query.text.length))
    }

    /** chat-view.tsx:2110-2115: a global-search result's query, once per nonce. */
    fun apply(query: String, nonce: Long): Boolean {
        if (query.isEmpty() || nonce == appliedNonce) return false
        appliedNonce = nonce
        this.query = TextFieldValue(query, TextRange(0, query.length))
        index = 0
        open = true
        return true
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<ChatFindState, Any>(
            save = { listOf(it.open, it.query.text, it.query.selection.start, it.query.selection.end, it.index, it.appliedNonce) },
            restore = { ChatFindState(it[0] as Boolean, TextFieldValue(it[1] as String, TextRange(it[2] as Int, it[3] as Int)), it[4] as Int, it[5] as Long) },
        )
    }
}

@Composable
internal fun rememberChatFindState(sessionId: String?): ChatFindState =
    rememberSaveable(sessionId, saver = ChatFindState.Saver) { ChatFindState(false, TextFieldValue(""), 0, 0L) }

/**
 * `.chat-find-bar` (globals.css 5691-5739): pinned top-right over the chat, `--graphite-raised`
 * on a 1px `--line-strong` edge, `--radius-md`, `--shadow-menu`, padded 6px 8px with
 * `--space-xs` gaps. The Search glyph, the query (15rem, at most 40vw; 0.85rem ink), the count in
 * words ("3 of 12" + a fainter " · turn 2 of 5", tabular, min 4.5rem, right-aligned — the
 * stronger yellow is never the only cue), then Previous / Next (disabled at 0.4 without a match)
 * and Close, 28px keys with a 48dp touch area. Enter / the IME action step to the next match,
 * Shift+Enter to the previous one, Escape closes.
 */
@Composable
internal fun ChatFindBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    count: FindCount,
    canStep: Boolean,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val t = LocalTetherTokens.current
    val type = LocalTetherTypography.current
    val shape = RoundedCornerShape(t.radiusMd)
    val viewportWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val inputWidth = minOf(240.dp, viewportWidth * 0.4f)
    Row(
        modifier
            .testTag(ChatFindTags.Bar)
            .cssSurface(shape, t.graphiteRaised, CssBorder(1.dp, t.lineStrong), t.css.shadowMenu)
            .padding(horizontal = 8.dp + 1.dp, vertical = 6.dp + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.css.spaceXs),
    ) {
        Icon(TetherIcons.Search, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
        val style = type.body.copy(fontSize = 13.6.sp, color = t.ink)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(t.violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
            modifier = Modifier
                .width(inputWidth)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onStep(if (event.isShiftPressed) -1 else 1)
                            true
                        }
                        Key.Escape -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                }
                .testTag(ChatFindTags.Input)
                .semantics { contentDescription = "Find in conversation" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.text.isEmpty()) Text("Find in conversation", style = style, color = t.faint, maxLines = 1)
                    inner()
                }
            },
        )
        val countStyle = type.body.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
        Text(
            buildAnnotatedString {
                append(count.primary)
                if (count.turn.isNotEmpty()) withStyle(SpanStyle(color = t.faint)) { append(count.turn) }
            },
            style = countStyle,
            color = t.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(ChatFindTags.Count),
        )
        FindKey(TetherIcons.ChevronUp, "Previous match", canStep, ChatFindTags.Previous) { onStep(-1) }
        FindKey(TetherIcons.ChevronDown, "Next match", canStep, ChatFindTags.Next) { onStep(1) }
        FindKey(TetherIcons.X, "Close find", true, ChatFindTags.Close, onClose)
    }
}

/** `.chat-find-nav` / `.chat-find-close`: 28px, `--radius-sm`, transparent, a muted 14px glyph. */
@Composable
private fun FindKey(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val t = LocalTetherTokens.current
    Box(
        Modifier
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = t.muted, modifier = Modifier.size(14.dp))
    }
}
