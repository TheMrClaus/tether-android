package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** The follow-the-bottom mode of one transcript: true until the reader scrolls up by hand. */
class FollowState {
    var sticky by mutableStateOf(true)
}

/** Where one session's transcript is: its list position (first visible row by key) and its follow mode. */
class TranscriptScroll internal constructor() {
    val listState: LazyListState = LazyListState()
    val follow: FollowState = FollowState()
}

/**
 * ta-jyj0: the transcript's scroll position outlives the shell that composed it. A phone turned sideways
 * switches PhoneShell <-> ExpandedShell, and each composes its own ChatScreen, so a list state remembered
 * inside the transcript started at the bottom every time. MainShell provides ONE store above that switch
 * (as it does the card store); the browser keeps the reader's place across a resize and so does this.
 *
 * It holds the ONE open session's position: another session replaces it (the web remounts its ChatView per
 * session, ta-coik.33: a chat opens at its latest message), and [retainOnly] drops it when no session is
 * showing.
 */
class TranscriptScrollStore {
    private var heldId: String? = null
    private var held: TranscriptScroll? = null

    /** This session's scroll; a different session than the held one starts fresh (and drops the old). */
    fun scrollFor(sessionId: String): TranscriptScroll {
        val current = held
        if (current != null && heldId == sessionId) return current
        return TranscriptScroll().also { held = it; heldId = sessionId }
    }

    /** Forget the held position unless it belongs to [sessionId] (null: no session is showing). */
    fun retainOnly(sessionId: String?) {
        if (heldId != sessionId) {
            held = null
            heldId = null
        }
    }
}

val LocalTranscriptScrollStore = staticCompositionLocalOf<TranscriptScrollStore?> { null }

/** The scroll for [sessionId]: the provided store's, or one private to this composition (tests, previews). */
@Composable
internal fun rememberTranscriptScroll(sessionId: String): TranscriptScroll {
    val store = LocalTranscriptScrollStore.current
    return if (store != null) remember(store, sessionId) { store.scrollFor(sessionId) } else remember(sessionId) { TranscriptScroll() }
}
