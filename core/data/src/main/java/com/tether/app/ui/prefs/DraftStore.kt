package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.protocol.helpers.DraftPreferences
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * T2.3: unsent composer text, per session, surviving a session switch and process death —
 * the web's `tether:draft:<sessionId>` localStorage entries (tether components/chat-view.tsx:
 * 1561-1584, written on every change at :1731-1737; the orphaned-create recovery in
 * hooks/use-draft-composer.ts:429-437 writes the same key).
 *
 * Contents are the TEXT only: attachments are never persisted (chat-view.tsx:1564-1566 — file
 * payloads would risk the storage quota), and model/effort/mode selections are not part of a
 * session draft (those are the per-provider [DraftPreferences.DRAFT_PREFS_KEY] record, kept
 * here too for the draft composer).
 *
 * Cleanup rule, exactly the web's: writing an empty draft REMOVES the key (on send, or when the
 * operator clears it by hand). There is no TTL and no sweep — the web never expires drafts.
 */
interface DraftStore {
    /** The stored draft, or "" when there is none. */
    suspend fun read(sessionId: String): String

    /** Store [text]; an empty text removes the entry. */
    suspend fun write(sessionId: String, text: String)

    suspend fun clear(sessionId: String) = write(sessionId, "")

    /** lib/draft-preferences.mjs readDraftPreferences: a non-object or junk value reads as {}. */
    suspend fun readDraftPreferences(): JsObj

    suspend fun writeDraftPreferences(preferences: JsObj)

    companion object {
        /** chat-view.tsx:1568 draftStorageKey. */
        fun key(sessionId: String): String = "tether:draft:$sessionId"
    }
}

private val Context.tetherDraftDataStore: DataStore<Preferences> by preferencesDataStore(name = "tether_drafts")

class DataStoreDraftStore internal constructor(private val store: DataStore<Preferences>) : DraftStore {
    constructor(context: Context) : this(context.applicationContext.tetherDraftDataStore)

    override suspend fun read(sessionId: String): String =
        store.data.first().asMap()[stringPreferencesKey(DraftStore.key(sessionId))] as? String ?: ""

    override suspend fun write(sessionId: String, text: String) {
        val key = stringPreferencesKey(DraftStore.key(sessionId))
        store.edit { if (text.isEmpty()) it.remove(key) else it[key] = text }
    }

    override suspend fun readDraftPreferences(): JsObj {
        val raw = store.data.first().asMap()[prefsKey] as? String ?: return JsObj.EMPTY
        return try {
            JsCodec.parse(raw) as? JsObj ?: JsObj.EMPTY
        } catch (_: RuntimeException) {
            JsObj.EMPTY // malformed — start clean, never crash (draft-preferences.mjs:17-19)
        }
    }

    override suspend fun writeDraftPreferences(preferences: JsObj) {
        store.edit { it[prefsKey] = JsCodec.stringify(preferences) }
    }

    companion object {
        private val prefsKey = stringPreferencesKey(DraftPreferences.DRAFT_PREFS_KEY)

        /** A store on an explicit file (tests; the app uses the Context constructor). */
        fun create(file: File, scope: CoroutineScope): DataStoreDraftStore =
            DataStoreDraftStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
}

/** Process-local drafts (previews, and the view-model default). */
class InMemoryDraftStore : DraftStore {
    private val drafts = HashMap<String, String>()
    private var draftPreferences: JsObj = JsObj.EMPTY

    override suspend fun read(sessionId: String): String = synchronized(drafts) { drafts[sessionId] ?: "" }

    override suspend fun write(sessionId: String, text: String) {
        synchronized(drafts) { if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text }
    }

    override suspend fun readDraftPreferences(): JsObj = draftPreferences

    override suspend fun writeDraftPreferences(preferences: JsObj) {
        draftPreferences = preferences
    }
}
