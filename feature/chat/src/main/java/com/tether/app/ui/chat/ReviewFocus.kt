package com.tether.app.ui.chat

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * ta-4711: the one request the Overview's "Review request" asked the transcript to land on (dashboard.tsx:1439-1442:
 * `scrollIntoView({ block: "center" })`, then `focus({ preventScroll: true })` on the card itself, never its Allow).
 * [requester] is the focus target of the card whose request id is [requestId].
 */
internal class ReviewFocus(val requestId: String, val requester: FocusRequester)

internal val LocalReviewFocus = staticCompositionLocalOf<ReviewFocus?> { null }

/**
 * `tabIndex={-1}` on `.chat-approval` / `.chat-question` (chat-view.tsx:1053, :1220): the card is a focus target only
 * from the request until focus leaves it again, never a standing Tab or D-pad stop. No indication is drawn (the web's
 * `:focus-visible` is false after a tap) and a non-editable target raises no keyboard. While focused the lazy item is
 * pinned, so the card keeps focus when the centring moves it off screen (`preventScroll`: no bring-into-view).
 */
@Composable
internal fun Modifier.reviewFocusTarget(requestId: String): Modifier {
    val review = LocalReviewFocus.current
    val targeted = review != null && review.requestId == requestId
    var focused by remember { mutableStateOf(false) }
    val armed = targeted || focused
    return this
        .then(if (targeted && review != null) Modifier.focusRequester(review.requester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .then(if (armed) Modifier.focusable() else Modifier)
}

@Composable
internal fun ProvideReviewFocus(review: ReviewFocus?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReviewFocus provides review, content = content)
}
