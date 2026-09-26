package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tetherUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "tether_ui_prefs")

/** DataStore-backed UI preferences (theme family, transcript options, push). */
class UiPrefs(context: Context) {
    private val store = context.applicationContext.tetherUiDataStore

    private object Keys {
        val theme = stringPreferencesKey("theme_choice")
        val showThinking = booleanPreferencesKey("show_thinking")
        val showEnded = booleanPreferencesKey("show_ended_sessions")
        val pinnedProjects = stringPreferencesKey("pinned_projects")
        val pushEnabled = booleanPreferencesKey("push_enabled")
        val pushScope = stringPreferencesKey("push_scope")
        val pushPermissionAsked = booleanPreferencesKey("push_permission_asked")
        // Newline-joined sets, matching pinnedProjects' pattern: paths/ids
        // can't contain newlines, and DataStore string sets are unordered.
        val pushAttachedSessions = stringPreferencesKey("push_attached_sessions")
        val pushPinnedSessions = stringPreferencesKey("push_pinned_sessions")
    }

    val themeChoice: Flow<ThemeChoice> = store.data.map { ThemeChoice.fromId(it[Keys.theme]) }

    suspend fun setThemeChoice(choice: ThemeChoice) {
        store.edit { it[Keys.theme] = choice.id }
    }

    val showThinking: Flow<Boolean> = store.data.map { it[Keys.showThinking] ?: true }

    suspend fun setShowThinking(value: Boolean) {
        store.edit { it[Keys.showThinking] = value }
    }

    val showEnded: Flow<Boolean> = store.data.map { it[Keys.showEnded] ?: false }

    suspend fun setShowEnded(value: Boolean) {
        store.edit { it[Keys.showEnded] = value }
    }

    /**
     * Starred project folders, in pin order (index caps + switch shortcuts key
     * off position, so order matters — newline-joined since paths can't
     * contain newlines and DataStore string sets are unordered).
     */
    val pinnedProjects: Flow<List<String>> = store.data.map {
        it[Keys.pinnedProjects]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
    }

    suspend fun setPinnedProjects(projects: List<String>) {
        store.edit { it[Keys.pinnedProjects] = projects.joinToString("\n") }
    }

    // ── Push notifications ────────────────────────────────────────────────

    /** Master toggle. Default ON: the first-launch UX prompts for permission. */
    val pushEnabled: Flow<Boolean> = store.data.map { it[Keys.pushEnabled] ?: true }

    suspend fun setPushEnabled(value: Boolean) {
        store.edit { it[Keys.pushEnabled] = value }
    }

    /** Per-device scope. Default [ThemeChoice.System]-independent: All events. */
    val pushScope: Flow<com.tether.app.push.PushScope> = store.data.map {
        com.tether.app.push.PushScope.fromWire(it[Keys.pushScope]) ?: com.tether.app.push.PushScope.All
    }

    suspend fun setPushScope(scope: com.tether.app.push.PushScope) {
        store.edit { it[Keys.pushScope] = scope.wire }
    }

    /** "Have we already asked for POST_NOTIFICATIONS?" — prompt at most once per user action. */
    val pushPermissionAsked: Flow<Boolean> = store.data.map { it[Keys.pushPermissionAsked] ?: false }

    suspend fun setPushPermissionAsked(value: Boolean) {
        store.edit { it[Keys.pushPermissionAsked] = value }
    }

    /** Session ids the device has attached to (drives the `attached` scope). */
    val attachedSessions: Flow<List<String>> = store.data.map {
        it[Keys.pushAttachedSessions]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
    }

    suspend fun setAttachedSessions(ids: Collection<String>) {
        store.edit { it[Keys.pushAttachedSessions] = ids.sorted().distinct().joinToString("\n") }
    }

    /** Session ids the device has pinned (drives the `pinned` scope). */
    val pinnedSessions: Flow<List<String>> = store.data.map {
        it[Keys.pushPinnedSessions]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
    }

    suspend fun setPinnedSessions(ids: Collection<String>) {
        store.edit { it[Keys.pushPinnedSessions] = ids.sorted().distinct().joinToString("\n") }
    }
}
