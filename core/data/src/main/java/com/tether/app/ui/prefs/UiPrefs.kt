package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tetherUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "tether_ui_prefs")

/**
 * DataStore-backed UI preferences: the web's `tether.preferences.v1` fields as one
 * [TetherPreferences] model (T2.3), plus the native-only push / permission state.
 */
class UiPrefs(
    /** Public for tests (T15.4: a shell test owns its store, so the boot view's inputs are its own). */
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.tetherUiDataStore)

    private object Keys {
        val theme = stringPreferencesKey(PreferenceKeys.LEGACY_THEME)
        val themeFamily = stringPreferencesKey(PreferenceKeys.THEME_FAMILY)
        val themeMode = stringPreferencesKey(PreferenceKeys.THEME_MODE)
        val showThinking = booleanPreferencesKey(PreferenceKeys.SHOW_THINKING)
        val showEnded = booleanPreferencesKey(PreferenceKeys.SHOW_ENDED_SESSIONS)
        val pinnedProjects = stringPreferencesKey(PreferenceKeys.PINNED_PROJECTS)
        val loginVariant = stringPreferencesKey(PreferenceKeys.LOGIN_VARIANT)
        val defaultWorkspace = stringPreferencesKey(PreferenceKeys.DEFAULT_WORKSPACE)
        val confirmBeforeEnd = booleanPreferencesKey(PreferenceKeys.CONFIRM_BEFORE_END)
        val sidebarCollapsed = booleanPreferencesKey(PreferenceKeys.SIDEBAR_COLLAPSED)
        val sidebarWidth = intPreferencesKey(PreferenceKeys.SIDEBAR_WIDTH)
        val inspectorWidth = intPreferencesKey(PreferenceKeys.INSPECTOR_WIDTH)
        val collapsedWorkspaces = stringPreferencesKey(PreferenceKeys.COLLAPSED_WORKSPACES)
        val lastSeenSessions = stringPreferencesKey(PreferenceKeys.LAST_SEEN_SESSIONS)
        val lastOpenedCwd = stringPreferencesKey(PreferenceKeys.LAST_OPENED_CWD)
        val lastOpenedSessionId = stringPreferencesKey(PreferenceKeys.LAST_OPENED_SESSION_ID)
        val lastOpenedHistoryId = stringPreferencesKey(PreferenceKeys.LAST_OPENED_HISTORY_ID)
        val sidebarActiveOnly = booleanPreferencesKey(PreferenceKeys.SIDEBAR_ACTIVE_ONLY)
        val sidebarUnreadOnly = booleanPreferencesKey(PreferenceKeys.SIDEBAR_UNREAD_ONLY)
        val sidebarHideAgentRuns = booleanPreferencesKey(PreferenceKeys.SIDEBAR_HIDE_AGENT_RUNS)
        val sidebarSort = stringPreferencesKey(PreferenceKeys.SIDEBAR_SORT)
        val pinnedModels = stringPreferencesKey(PreferenceKeys.PINNED_MODELS)

        // Native-only (no web counterpart).
        val pushEnabled = booleanPreferencesKey("push_enabled")
        val pushScope = stringPreferencesKey("push_scope")
        val pushPermissionAsked = booleanPreferencesKey("push_permission_asked")
        val localNetworkPermissionAsked = booleanPreferencesKey("local_network_permission_asked")
        // Newline-joined sets, matching pinnedProjects' pattern: paths/ids
        // can't contain newlines, and DataStore string sets are unordered.
        val pushAttachedSessions = stringPreferencesKey("push_attached_sessions")
        val pushPinnedSessions = stringPreferencesKey("push_pinned_sessions")

        // T15.4 (lib/dashboard-view.mjs VIEW_STORAGE_KEY `tether:lastView`): the last top-level
        // view, a key of its own beside the preference model, as on the web.
        val lastView = stringPreferencesKey("last_view")
    }

    /**
     * The whole web preference model, parsed fail-soft per field ([TetherPreferences.parse]):
     * a wrongly-typed or junk stored value reads as that field's default, never a crash.
     */
    val preferences: Flow<TetherPreferences> = store.data.map(::parse).distinctUntilChanged()

    /** Atomic read-modify-write of the model (the web's `update(next)` with a fresh read). */
    suspend fun updatePreferences(transform: (TetherPreferences) -> TetherPreferences) {
        store.edit { prefs -> write(prefs, transform(parse(prefs))) }
    }

    private fun <T> field(select: (TetherPreferences) -> T): Flow<T> = preferences.map(select).distinctUntilChanged()

    /**
     * The web's `loginVariant` preference (Settings → Sign-in screen): Retro is
     * the opt-in, anything else is Instrument (hooks/use-preferences.ts). The
     * Settings control arrives with the settings surface; the login screen
     * already honours the stored value.
     */
    val loginVariant: Flow<LoginVariant> = field { it.loginVariant }

    suspend fun setLoginVariant(variant: LoginVariant) = updatePreferences { it.copy(loginVariant = variant) }

    /** Family × mode (web `themeFamily`/`themeMode`); a legacy flat `theme_choice` is migrated on read. */
    val themeChoice: Flow<ThemeChoice> = field { it.theme }

    suspend fun setThemeChoice(choice: ThemeChoice) = updatePreferences { it.copy(theme = choice) }

    val showThinking: Flow<Boolean> = field { it.showThinking }

    suspend fun setShowThinking(value: Boolean) = updatePreferences { it.copy(showThinking = value) }

    val showEnded: Flow<Boolean> = field { it.showEndedSessions }

    suspend fun setShowEnded(value: Boolean) = updatePreferences { it.copy(showEndedSessions = value) }

    /**
     * Starred project folders, in pin order (index caps + switch shortcuts key
     * off position, so order matters — newline-joined since paths can't
     * contain newlines and DataStore string sets are unordered).
     */
    val pinnedProjects: Flow<List<String>> = field { it.pinnedProjects }

    suspend fun setPinnedProjects(projects: List<String>) = updatePreferences { it.copy(pinnedProjects = projects) }

    private companion object {
        fun parse(prefs: Preferences): TetherPreferences =
            TetherPreferences.parse(prefs.asMap().entries.associate { (key, value) -> key.name to value })

        /** Every model field is written back, like the web's whole-object save. */
        fun write(prefs: MutablePreferences, next: TetherPreferences) {
            prefs[Keys.themeFamily] = next.theme.family.id
            prefs[Keys.themeMode] = next.theme.mode.id
            // Like the web (use-preferences.ts:334), the legacy flat id is dropped once the two axes are stored.
            prefs.remove(Keys.theme)
            prefs[Keys.loginVariant] = next.loginVariant.id
            prefs[Keys.defaultWorkspace] = next.defaultWorkspace
            prefs[Keys.showEnded] = next.showEndedSessions
            prefs[Keys.confirmBeforeEnd] = next.confirmBeforeEnd
            prefs[Keys.showThinking] = next.showThinking
            prefs[Keys.sidebarCollapsed] = next.sidebarCollapsed
            prefs.putOrRemove(Keys.sidebarWidth, next.sidebarWidth)
            prefs.putOrRemove(Keys.inspectorWidth, next.inspectorWidth)
            prefs[Keys.pinnedProjects] = TetherPreferences.joinLines(next.pinnedProjects)
            prefs[Keys.collapsedWorkspaces] = TetherPreferences.joinLines(next.collapsedWorkspaces)
            prefs[Keys.lastSeenSessions] = TetherPreferences.joinSeen(next.lastSeenSessions)
            val opened = next.lastOpenedSession
            prefs.putOrRemove(Keys.lastOpenedCwd, opened?.cwd)
            prefs.putOrRemove(Keys.lastOpenedSessionId, opened?.sessionId)
            prefs.putOrRemove(Keys.lastOpenedHistoryId, opened?.historyId)
            prefs[Keys.sidebarActiveOnly] = next.sidebarActiveOnly
            prefs[Keys.sidebarUnreadOnly] = next.sidebarUnreadOnly
            prefs[Keys.sidebarHideAgentRuns] = next.sidebarHideAgentRuns
            prefs[Keys.sidebarSort] = next.sidebarSort.id
            prefs[Keys.pinnedModels] = TetherPreferences.joinLines(next.pinnedModels)
        }

        fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
            if (value == null) remove(key) else this[key] = value
        }
    }

    // ── Top-level view (T15.4) ────────────────────────────────────────────

    /**
     * What a boot of the console resolves its view from (dashboard.tsx `bootView`): the remembered
     * last view, and whether this install already kept Tether preferences before that record
     * existed (the web tests its preferences key; here the model's stored theme axes, written by
     * every save of the model, or the legacy flat theme). Unreadable storage reads as a fresh
     * install, like the web's blocked localStorage.
     */
    suspend fun viewBoot(): ViewBoot = runCatching {
        val stored = store.data.first()
        ViewBoot(
            storedView = stored[Keys.lastView],
            hasExistingPreferences = Keys.themeFamily in stored || Keys.theme in stored,
        )
    }.getOrElse { ViewBoot(storedView = null, hasExistingPreferences = false) }

    /** Remember the top-level view on screen (dashboard.tsx `writeStoredView`). */
    suspend fun setLastView(view: String) {
        runCatching { store.edit { it[Keys.lastView] = view } }
    }

    // ── Push notifications ────────────────────────────────────────────────

    /** Master toggle. Default ON: the first-launch UX prompts for permission. */
    val pushEnabled: Flow<Boolean> = store.data.map { it[Keys.pushEnabled] ?: true }

    suspend fun setPushEnabled(value: Boolean) {
        store.edit { it[Keys.pushEnabled] = value }
    }

    /** Per-device push scope (independent of the theme). Default: All events. */
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

    /**
     * "Have we ever shown the system ACCESS_LOCAL_NETWORK dialog?" (Android 17+).
     * Tells "never asked" apart from "denied, don't ask again": both report
     * shouldShowRequestPermissionRationale == false, but only the second one needs
     * the app-settings deep link.
     */
    val localNetworkPermissionAsked: Flow<Boolean> = store.data.map { it[Keys.localNetworkPermissionAsked] ?: false }

    suspend fun setLocalNetworkPermissionAsked(value: Boolean) {
        store.edit { it[Keys.localNetworkPermissionAsked] = value }
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

/** Stored sign-in screen choice; see [UiPrefs.loginVariant]. */
enum class LoginVariant(val id: String) {
    Instrument("instrument"),
    Retro("retro");

    companion object {
        fun fromId(id: String?): LoginVariant = if (id == Retro.id) Retro else Instrument
    }
}

/** T15.4: the stored inputs of the console's boot view ([UiPrefs.viewBoot]). */
data class ViewBoot(val storedView: String?, val hasExistingPreferences: Boolean)
