package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.client.serverOrigin
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
 * T7.1: per server ORIGIN too, as the web's localStorage is (and as T1.3/ta-s8q keys unsent
 * input). [origin] is the canonical `scheme://host:port` of [com.tether.app.client.serverOrigin];
 * a draft typed for a session on server A is never offered to server B. The stored key is
 * [key] = `<origin>|tether:draft:<sessionId>`.
 *
 * Contents are the TEXT only: attachments are never persisted (chat-view.tsx:1564-1566 — file
 * payloads would risk the storage quota), and model/effort/mode selections are not part of a
 * session draft (those are the per-provider [DraftPreferences.DRAFT_PREFS_KEY] record, kept
 * here too for the draft composer).
 *
 * Cleanup rule, exactly the web's: writing an empty draft REMOVES the key (on send, or when the
 * operator clears it by hand). There is no TTL and no sweep — the web never expires drafts, and a
 * logout keeps them (dashboard.tsx:1068 logout leaves localStorage alone; T1.3's pending slots
 * are kept across logout the same way). Drafts are user content: the file is excluded from cloud
 * backup and device transfer ([FILE_NAME], app/src/main/res/xml/data_extraction_rules.xml).
 *
 * Unscoped keys written by pre-T7.1 development builds (`tether:draft:<id>`, never in a
 * release) are not attributed to any origin: they are never read, and the next write drops them.
 */
interface DraftStore {
    /** The stored draft of [sessionId] on [origin], or "" when there is none. */
    suspend fun read(origin: String, sessionId: String): String

    /** Store [text] for [sessionId] on [origin]; an empty text removes the entry. */
    suspend fun write(origin: String, sessionId: String, text: String)

    suspend fun clear(origin: String, sessionId: String) = write(origin, sessionId, "")

    /** lib/draft-preferences.mjs readDraftPreferences: a non-object or junk value reads as {}. */
    suspend fun readDraftPreferences(): JsObj

    suspend fun writeDraftPreferences(preferences: JsObj)

    companion object {
        /** DataStore's file for the drafts (under `files/datastore/`), excluded from backup. */
        const val FILE_NAME = "tether_drafts.preferences_pb"

        /** chat-view.tsx:1568 draftStorageKey. */
        fun webKey(sessionId: String): String = "tether:draft:$sessionId"

        /** The stored key: [webKey] inside [origin]'s namespace. [origin] must be canonical. */
        fun key(origin: String, sessionId: String): String {
            require(serverOrigin(origin) == origin) { "not a canonical server origin" }
            return "$origin|${webKey(sessionId)}"
        }

        /** A pre-T7.1 unscoped draft key. */
        fun isLegacyKey(name: String): Boolean = name.startsWith("tether:draft:")
    }
}

private val Context.tetherDraftDataStore: DataStore<Preferences> by preferencesDataStore(name = DraftStore.FILE_NAME.removeSuffix(".preferences_pb"))

class DataStoreDraftStore internal constructor(private val store: DataStore<Preferences>) : DraftStore {
    constructor(context: Context) : this(context.applicationContext.tetherDraftDataStore)

    override suspend fun read(origin: String, sessionId: String): String =
        store.data.first().asMap()[stringPreferencesKey(DraftStore.key(origin, sessionId))] as? String ?: ""

    override suspend fun write(origin: String, sessionId: String, text: String) {
        val key = stringPreferencesKey(DraftStore.key(origin, sessionId))
        store.edit { prefs ->
            prefs.asMap().keys.filter { DraftStore.isLegacyKey(it.name) }.forEach { prefs.remove(it) }
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
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

    override suspend fun read(origin: String, sessionId: String): String =
        synchronized(drafts) { drafts[DraftStore.key(origin, sessionId)] ?: "" }

    override suspend fun write(origin: String, sessionId: String, text: String) {
        val key = DraftStore.key(origin, sessionId)
        synchronized(drafts) { if (text.isEmpty()) drafts.remove(key) else drafts[key] = text }
    }

    override suspend fun readDraftPreferences(): JsObj = draftPreferences

    override suspend fun writeDraftPreferences(preferences: JsObj) {
        draftPreferences = preferences
    }
}
