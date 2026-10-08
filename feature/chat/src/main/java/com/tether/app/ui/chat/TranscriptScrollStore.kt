package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The follow-the-bottom mode of one transcript: true until the reader scrolls up by hand. */
class FollowState {
    var sticky by mutableStateOf(true)
}

/** Where a transcript was: its first visible row by key (not index: rows come and go) and how far into it. */
class TranscriptAnchor(val key: String, val offset: Int)

/** Where one session's transcript is: its list position (first visible row by key) and its follow mode. */
class TranscriptScroll internal constructor(
    sticky: Boolean = true,
    private var restore: TranscriptAnchor? = null,
    toggles: GroupToggles = GroupToggles(),
) {
    val listState: LazyListState = LazyListState()

    /**
     * ta-twjm: the reader's activity-group toggles, held with the scroll so a shell switch and an activity recreation
     * keep them as the browser keeps its `<details>` open across a resize.
     */
    internal val groupToggles: GroupToggles = toggles
    val follow: FollowState = FollowState().also { it.sticky = sticky }

    /** The place a recreated activity left, to be put back once rows exist; handed out once. */
    internal fun takeRestore(): TranscriptAnchor? = restore.also { restore = null }

    /** Still waiting to be put back (the activity is recreated again before its rows existed). */
    internal fun pendingRestore(): TranscriptAnchor? = restore

    /** The reader's place as plain data: the first visible row's key and offset into it (null: none to name). */
    internal fun anchor(): TranscriptAnchor? {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex } ?: return null
        val key = first.key as? String ?: return null
        return TranscriptAnchor(key, listState.firstVisibleItemScrollOffset)
    }
}

/**
 * ta-jyj0: the transcript's scroll position outlives the shell that composed it AND the activity. A phone
 * turned sideways recreates the activity (MainActivity declares no configChanges; only ShareActivity does)
 * and switches PhoneShell <-> ExpandedShell, each composing its own ChatScreen, so a list state remembered
 * inside the transcript started at the bottom every time. MainShell holds ONE store above that switch (as it
 * does the card store) and saves it across the recreation as plain data ([Saver]: the session, the follow
 * mode, the first visible row's key and offset). The browser keeps the reader's place across a resize and
 * so does this.
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

    /** One JSON string (a plain value any saved-state bundle takes); "" when nothing is held. */
    internal fun encode(): String {
        val id = heldId ?: return ""
        val scroll = held ?: return ""
        val sticky = scroll.follow.sticky
        val place = if (sticky) null else scroll.anchor() ?: scroll.pendingRestore()
        return buildJsonObject {
            put("id", id)
            put("sticky", sticky)
            put("toggles", buildJsonArray { scroll.groupToggles.snapshot().forEach { (k, v) -> add(JsonPrimitive(GroupToggles.encodeEntry(k, v))) } })
            if (place != null) {
                put("key", place.key)
                put("offset", place.offset)
            }
        }.toString()
    }

    companion object {
        internal fun decode(raw: String): TranscriptScrollStore = TranscriptScrollStore().also { store ->
            runCatching {
                val o = Json.parseToJsonElement(raw).jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                val sticky = o["sticky"]?.jsonPrimitive?.boolean ?: true
                val key = o["key"]?.jsonPrimitive?.content
                val offset = o["offset"]?.jsonPrimitive?.int ?: 0
                store.heldId = id
                val toggles = GroupToggles(o["toggles"]?.jsonArray?.associate { GroupToggles.decodeEntry(it.jsonPrimitive.content) } ?: emptyMap())
                store.held = TranscriptScroll(sticky, key?.let { TranscriptAnchor(it, offset) }, toggles)
            } // an unreadable record is no record: the chat opens at its latest message
        }

        val Saver: androidx.compose.runtime.saveable.Saver<TranscriptScrollStore, String> =
            androidx.compose.runtime.saveable.Saver(save = { it.encode() }, restore = { decode(it) })
    }
}

val LocalTranscriptScrollStore = staticCompositionLocalOf<TranscriptScrollStore?> { null }

/** The scroll for [sessionId]: the provided store's, or one private to this composition (tests, previews). */
@Composable
internal fun rememberTranscriptScroll(sessionId: String): TranscriptScroll {
    val store = LocalTranscriptScrollStore.current
    return if (store != null) remember(store, sessionId) { store.scrollFor(sessionId) } else remember(sessionId) { TranscriptScroll() }
}
