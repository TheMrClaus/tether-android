package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.ui.theme.ThemeMode
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tetherUiDataStore: DataStore<Preferences> by preferencesDataStore(name = "tether_ui_prefs")

/**
 * DataStore-backed UI preferences: the web's `tether.preferences.v1` fields as one
 * [TetherPreferences] model (T2.3), plus the native-only push / permission state.
 */
class UiPrefs internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.tetherUiDataStore)

    /**
     * What a write the disk refused would have stored (null: the disk copy is current), shared by
     * every [UiPrefs] on [store]: the web's module-level `memoryCache` while `persistenceBroken`
     * (hooks/use-preferences.ts), so a failed save does not silently revert this process.
     */
    private val kept: MutableStateFlow<Preferences?> = synchronized(keptByStore) {
        keptByStore.getOrPut(store) { MutableStateFlow(null) }
    }

    /** The stored preferences, or what a refused write kept in memory. */
    private val data: Flow<Preferences> = combine(store.data, kept) { stored, memory -> memory ?: stored }

    /**
     * Every write: an atomic edit on top of what [data] shows. A write the disk refuses still
     * throws, but its result is [kept] and served until a later write lands (the web's
     * `localStorage.setItem` failing under its in-memory value).
     */
    private suspend fun save(change: (MutablePreferences) -> Unit) {
        var next: Preferences? = null
        try {
            store.updateData { stored -> (kept.value ?: stored).toMutablePreferences().apply(change).toPreferences().also { next = it } }
            kept.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            next?.let { kept.value = it }
            throw e
        }
    }

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
    val preferences: Flow<TetherPreferences> = data.map(::parse).distinctUntilChanged()

    /** Atomic read-modify-write of the model (the web's `update(next)` with a fresh read). */
    suspend fun updatePreferences(transform: (TetherPreferences) -> TetherPreferences) {
        save { prefs -> write(prefs, transform(parse(prefs))) }
    }

    private fun <T> field(select: (TetherPreferences) -> T): Flow<T> = preferences.map(select).distinctUntilChanged()

    /**
     * The web's `loginVariant` preference (Settings → Sign-in screen): Retro is
     * the opt-in, anything else (the former `instrument` included) is Default
     * (lib/theme-mode.mjs normalizeLoginVariant). The
     * Settings control arrives with the settings surface; the login screen
     * already honours the stored value.
     */
    val loginVariant: Flow<LoginVariant> = field { it.loginVariant }

    suspend fun setLoginVariant(variant: LoginVariant) = updatePreferences { it.copy(loginVariant = variant) }

    /** Studio light / dark / follow system (web `themeMode`); retired theme keys are migrated on read. */
    val themeMode: Flow<ThemeMode> = field { it.themeMode }

    suspend fun setThemeMode(mode: ThemeMode) = updatePreferences { it.copy(themeMode = mode) }

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

    companion object {
        /** T15.4: preferences on a store of the caller's own (a shell test's, so its boot view's inputs are its own). */
        fun on(store: DataStore<Preferences>): UiPrefs = UiPrefs(store)

        private val keptByStore = WeakHashMap<DataStore<Preferences>, MutableStateFlow<Preferences?>>()

        private fun parse(prefs: Preferences): TetherPreferences =
            TetherPreferences.parse(prefs.asMap().entries.associate { (key, value) -> key.name to value })

        /** Every model field is written back, like the web's whole-object save. */
        private fun write(prefs: MutablePreferences, next: TetherPreferences) {
            prefs[Keys.themeMode] = next.themeMode.id
            // Like the web (lib/theme-mode.mjs DEPRECATED_THEME_KEYS), the retired family and the
            // legacy flat id are consumed by the read and never saved again.
            prefs.remove(Keys.theme)
            prefs.remove(Keys.themeFamily)
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

        private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
            if (value == null) remove(key) else this[key] = value
        }
    }

    // ── Top-level view (T15.4) ────────────────────────────────────────────

    /**
     * What a boot of the console resolves its view from (dashboard.tsx `bootView`): the remembered
     * last view, and whether this install already kept Tether preferences before that record
     * existed (the web tests its preferences key; here the model's stored theme mode, written by
     * every save of the model, or a retired theme key an older version wrote). Unreadable storage reads as a fresh
     * install, like the web's blocked localStorage.
     */
    suspend fun viewBoot(): ViewBoot = runCatching {
        val stored = data.first()
        ViewBoot(
            storedView = stored[Keys.lastView],
            hasExistingPreferences = Keys.themeMode in stored || Keys.themeFamily in stored || Keys.theme in stored,
        )
    }.getOrElse { ViewBoot(storedView = null, hasExistingPreferences = false) }

    /** Remember the top-level view on screen (dashboard.tsx `writeStoredView`). */
    suspend fun setLastView(view: String) {
        runCatching { save { it[Keys.lastView] = view } }
    }

    // ── Push notifications ────────────────────────────────────────────────

    /** Master toggle. Default ON: the first-launch UX prompts for permission. */
    val pushEnabled: Flow<Boolean> = data.map { it[Keys.pushEnabled] ?: true }

    suspend fun setPushEnabled(value: Boolean) {
        save { it[Keys.pushEnabled] = value }
    }

    /** Per-device push scope (independent of the theme). Default: All events. */
    val pushScope: Flow<com.tether.app.push.PushScope> = data.map {
        com.tether.app.push.PushScope.fromWire(it[Keys.pushScope]) ?: com.tether.app.push.PushScope.All
    }

    suspend fun setPushScope(scope: com.tether.app.push.PushScope) {
        save { it[Keys.pushScope] = scope.wire }
    }

    /** "Have we already asked for POST_NOTIFICATIONS?" — prompt at most once per user action. */
    val pushPermissionAsked: Flow<Boolean> = data.map { it[Keys.pushPermissionAsked] ?: false }

    suspend fun setPushPermissionAsked(value: Boolean) {
        save { it[Keys.pushPermissionAsked] = value }
    }

    /**
     * "Have we ever shown the system ACCESS_LOCAL_NETWORK dialog?" (Android 17+).
     * Tells "never asked" apart from "denied, don't ask again": both report
     * shouldShowRequestPermissionRationale == false, but only the second one needs
     * the app-settings deep link.
     */
    val localNetworkPermissionAsked: Flow<Boolean> = data.map { it[Keys.localNetworkPermissionAsked] ?: false }

    suspend fun setLocalNetworkPermissionAsked(value: Boolean) {
        save { it[Keys.localNetworkPermissionAsked] = value }
    }

    /** Session ids the device has attached to (drives the `attached` scope). */
    val attachedSessions: Flow<List<String>> = data.map {
        it[Keys.pushAttachedSessions]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
    }

    suspend fun setAttachedSessions(ids: Collection<String>) {
        save { it[Keys.pushAttachedSessions] = ids.sorted().distinct().joinToString("\n") }
    }

    /** Session ids the device has pinned (drives the `pinned` scope). */
    val pinnedSessions: Flow<List<String>> = data.map {
        it[Keys.pushPinnedSessions]?.split('\n')?.filter(String::isNotBlank) ?: emptyList()
    }

    suspend fun setPinnedSessions(ids: Collection<String>) {
        save { it[Keys.pushPinnedSessions] = ids.sorted().distinct().joinToString("\n") }
    }
}

/** Stored sign-in screen choice; see [UiPrefs.loginVariant]. */
enum class LoginVariant(val id: String) {
    /** Studio's own sign-in (the web renamed the former `instrument` choice to "Default"). */
    Default("default"),
    Retro("retro");

    companion object {
        fun fromId(id: String?): LoginVariant = if (id == Retro.id) Retro else Default
    }
}

/** T15.4: the stored inputs of the console's boot view ([UiPrefs.viewBoot]). */
data class ViewBoot(val storedView: String?, val hasExistingPreferences: Boolean)
