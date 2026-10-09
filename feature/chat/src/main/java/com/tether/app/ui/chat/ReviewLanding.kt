package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * ta-4711 (dashboard.tsx:1424-1445), shared (ta-d0qg) by the transcript and a run tab: "Review request" lands on the exact
 * card. The card spans the lazy items [head]..[last] ([gapHead] px of space above the head belong to no card). It is brought to
 * the viewport's centre (`scrollIntoView({ block: "center" })`, instant, the list's ends clamp it), then focused
 * (`focus({ preventScroll: true })`: the card itself, never its Allow). Focus comes BEFORE the centring so the focused row stays
 * pinned when the centring moves it off screen (no bring-into-view afterwards). [onMovedUp] is called when the net move went
 * toward older rows (the follow mode stops following, chat-view.tsx:1947-1955); a move down leaves it as it was. The caller
 * guards its own pins around the call.
 */
internal suspend fun landOnReviewCard(
    listState: LazyListState,
    requester: FocusRequester,
    head: Int,
    last: Int,
    gapHead: Float,
    onMovedUp: () -> Unit,
) {
    val startIndex = listState.firstVisibleItemIndex
    val startOffset = listState.firstVisibleItemScrollOffset
    listState.scrollToItem(head)
    withFrameNanos { }
    var focused = false
    var tries = 0
    while (!focused && tries++ < 5) {
        focused = try {
            requester.requestFocus()
        } catch (_: IllegalStateException) {
            false
        }
        if (!focused) withFrameNanos { }
    }
    // Compose has no `preventScroll`: a gained focus asks the scroller to show the card, a frame or two later.
    // Let that settle first, so the centring below is the last word.
    repeat(3) { withFrameNanos { } }
    while (listState.isScrollInProgress) withFrameNanos { }
    // The card's extent in one frame of reference (the scrolled distance from the head at the top).
    val tops = HashMap<Int, Float>()
    val bottoms = HashMap<Int, Float>()
    var travelled = 0f
    fun note() {
        for (info in listState.layoutInfo.visibleItemsInfo) {
            if (info.index in head..last) {
                tops[info.index] = info.offset + travelled
                bottoms[info.index] = info.offset + info.size + travelled
            }
        }
    }
    note()
    var steps = 0
    while (last !in bottoms && steps++ < 400) {
        val consumed = listState.scrollBy(listState.layoutInfo.viewportSize.height * 0.8f)
        if (consumed == 0f) break
        travelled += consumed
        note()
    }
    val spanTop = (tops[head] ?: 0f) + gapHead
    val spanBottom = bottoms[last] ?: bottoms.values.maxOrNull() ?: spanTop
    val info = listState.layoutInfo
    val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val want = (spanTop + spanBottom) / 2f - travelled - viewportCentre
    listState.scrollBy(want)
    // The focus's own bring-into-view may still be in flight: what it moves, the centring takes back.
    val landedIndex = listState.firstVisibleItemIndex
    val landedOffset = listState.firstVisibleItemScrollOffset
    repeat(2) { withFrameNanos { } }
    if (listState.firstVisibleItemIndex != landedIndex || listState.firstVisibleItemScrollOffset != landedOffset) {
        listState.scrollToItem(landedIndex, landedOffset)
    }
    if (listState.firstVisibleItemIndex < startIndex ||
        (listState.firstVisibleItemIndex == startIndex && listState.firstVisibleItemScrollOffset < startOffset)
    ) {
        onMovedUp()
    }
    withFrameNanos { }
}
