package com.tether.app.ui.chat

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * ta-4711: the one request the Overview's "Review request" asked the transcript to land on (dashboard.tsx:1439-1442:
 * `scrollIntoView({ block: "center" })`, then `focus({ preventScroll: true })` on the card itself, never its Allow).
 * [requester] is the focus target of the card whose request id is [requestId].
 */
internal class ReviewFocus(val requestId: String, val requester: FocusRequester)

internal val LocalReviewFocus = staticCompositionLocalOf<ReviewFocus?> { null }

/**
 * ta-xhs4: whether the card that holds the review focus draws the web's `:focus-visible` ring (`[tabindex]:focus-visible
 * { outline: 2px solid var(--violet); outline-offset: 2px }`, globals.css:83-86; `.chat-approval` and `.chat-question`
 * have no override). Chromium sets focus-visible at the moment of focus, from the input that caused it, and keeps it while
 * the element stays focused: [drawn] is that bit, set on focus gain from the input mode ([reviewFocusTarget]) and cleared on
 * blur, and by a press on a control inside the card ([clearsReviewRing]: on the web focus moves to the control, which is no
 * longer the card).
 */
@Stable
internal class ReviewRing {
    var drawn by mutableStateOf(false)
}

/** The ring of the card whose controls are being composed; null outside a card. */
internal val LocalReviewRing = staticCompositionLocalOf<ReviewRing?> { null }

/**
 * A press on a control inside the card (a grant row, the network and confirm rows, Allow, Decline, an option, Skip, Next,
 * Submit) takes the card's ring away, as focus moving to that control does on the web. A scroll or a press on the card's plain
 * text leaves it (Compose does not move focus on a touch of a toggleable). The press is only observed: the control sees it.
 */
@Composable
internal fun Modifier.clearsReviewRing(): Modifier {
    val ring = LocalReviewRing.current ?: return this
    return pointerInput(ring) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) ring.drawn = false
            }
        }
    }
}

/**
 * `tabIndex={-1}` on `.chat-approval` / `.chat-question` (chat-view.tsx:1053, :1220): the card is a focus target only
 * from the request until focus leaves it again, never a standing Tab or D-pad stop. The ring ([ReviewRing]) is drawn only
 * when the focus came by keyboard (the web's `:focus-visible` is false after a tap), decided once, when focus arrives, and
 * a non-editable target raises no keyboard. While focused the lazy item is pinned, so the card keeps focus when the centring
 * moves it off screen (`preventScroll`: no bring-into-view).
 */
@Composable
internal fun Modifier.reviewFocusTarget(requestId: String, ring: ReviewRing): Modifier {
    val review = LocalReviewFocus.current
    val targeted = review != null && review.requestId == requestId
    var focused by remember { mutableStateOf(false) }
    val input = LocalInputModeManager.current
    val held = remember { booleanArrayOf(false) }
    DisposableEffect(ring) { onDispose { ring.drawn = false } }
    val armed = targeted || focused
    return this
        .then(if (targeted && review != null) Modifier.focusRequester(review.requester) else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && !held[0]) ring.drawn = input.inputMode == InputMode.Keyboard
            if (!it.isFocused) ring.drawn = false
            held[0] = it.isFocused
        }
        .then(if (armed) Modifier.focusable() else Modifier)
}

@Composable
internal fun ProvideReviewFocus(review: ReviewFocus?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReviewFocus provides review, content = content)
}
