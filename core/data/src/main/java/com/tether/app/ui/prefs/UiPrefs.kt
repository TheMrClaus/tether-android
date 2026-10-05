package com.tether.app.ui.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tether.app.client.serverOrigin
import com.tether.app.ui.theme.ThemeMode
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
        val lastOpenedByOrigin = stringPreferencesKey(PreferenceKeys.LAST_OPENED_BY_ORIGIN)
        val lastOpenedByOriginJson = stringPreferencesKey(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON)
        val collapsedByOriginJson = stringPreferencesKey(PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON)
        val lastSeenByOriginJson = stringPreferencesKey(PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON)
        val preferencesByOriginJson = stringPreferencesKey(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON)
        val overviewFiltersByOriginJson = stringPreferencesKey(PreferenceKeys.OVERVIEW_FILTERS_BY_ORIGIN_JSON)
        val sidebarActiveOnly = booleanPreferencesKey(PreferenceKeys.SIDEBAR_ACTIVE_ONLY)
        val sidebarUnreadOnly = booleanPreferencesKey(PreferenceKeys.SIDEBAR_UNREAD_ONLY)
        val sidebarHideAgentRuns = booleanPreferencesKey(PreferenceKeys.SIDEBAR_HIDE_AGENT_RUNS)
        val sidebarSort = stringPreferencesKey(PreferenceKeys.SIDEBAR_SORT)
        val pinnedModels = stringPreferencesKey(PreferenceKeys.PINNED_MODELS)

        /**
         * ta-coik.52: the device-wide keys of the fields now kept per server ([ServerPreferences]),
         * written only while a device-wide value waits to be migrated.
         */
        val deviceWide: List<Preferences.Key<*>> = listOf(
            themeMode, loginVariant, defaultWorkspace, showEnded, confirmBeforeEnd, showThinking, sidebarCollapsed,
            sidebarWidth, inspectorWidth, pinnedProjects, sidebarActiveOnly, sidebarUnreadOnly, sidebarHideAgentRuns,
            sidebarSort, pinnedModels,
        )

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
        // view, a key of its own beside the preference model, as on the web. ta-coik.47: device-wide
        // before, read only until the first write moves it to a server.
        val lastView = stringPreferencesKey("last_view")
        // ta-coik.47: `{ origin: view }` (the web's localStorage is per origin).
        val lastViewByOrigin = stringPreferencesKey(PreferenceKeys.LAST_VIEW_BY_ORIGIN_JSON)
    }

    /**
     * The whole web preference model as stored, parsed fail-soft per field ([TetherPreferences.parse]):
     * a wrongly-typed or junk stored value reads as that field's default, never a crash. ta-coik.52:
     * every web preference is per server, so a screen reads [preferencesFor] its server; this is the
     * stored model (the migration's input).
     */
    val preferences: Flow<TetherPreferences> = data.map(::parse).distinctUntilChanged()

    /** Atomic read-modify-write of the model (the web's `update(next)` with a fresh read). */
    suspend fun updatePreferences(transform: (TetherPreferences) -> TetherPreferences) {
        save { prefs -> write(prefs, transform(parse(prefs))) }
    }

    /**
     * ta-coik.47: the model as the server at the origin of [serverUrl] sees it
     * ([TetherPreferences.forServer]: its folded blocks, seen stamps and remembered chat; ta-coik.52:
     * and every other preference, the web's localStorage being per origin). A null URL (no server
     * configured) reads the "" record, else the device-wide values not yet migrated, else the defaults.
     */
    fun preferencesFor(serverUrl: Flow<String?>): Flow<TetherPreferences> =
        combine(preferences, serverUrl) { p, url -> p.forServer(serverOrigin(url)) }.distinctUntilChanged()

    /** ta-coik.47: [updatePreferences] on [origin]'s view ([TetherPreferences.updateForServer]). */
    suspend fun updatePreferencesFor(origin: String?, transform: (TetherPreferences) -> TetherPreferences) =
        updatePreferences { it.updateForServer(origin, transform) }

    private fun <T> field(serverUrl: Flow<String?>, select: (TetherPreferences) -> T): Flow<T> =
        preferencesFor(serverUrl).map(select).distinctUntilChanged()

    /**
     * The web's `loginVariant` preference (Settings → Sign-in screen) for the server at [serverUrl]
     * (ta-coik.52: the web's sign-in page reads its own origin's): Retro is the opt-in, anything else
     * (the former `instrument` included) is Default (lib/theme-mode.mjs normalizeLoginVariant).
     */
    fun loginVariant(serverUrl: Flow<String?>): Flow<LoginVariant> = field(serverUrl) { it.loginVariant }

    suspend fun setLoginVariant(origin: String?, variant: LoginVariant) = updatePreferencesFor(origin) { it.copy(loginVariant = variant) }

    /** Studio light / dark / follow system (web `themeMode`) for the server at [serverUrl]; retired theme keys are migrated on read. */
    fun themeMode(serverUrl: Flow<String?>): Flow<ThemeMode> = field(serverUrl) { it.themeMode }

    suspend fun setThemeMode(origin: String?, mode: ThemeMode) = updatePreferencesFor(origin) { it.copy(themeMode = mode) }

    fun showThinking(serverUrl: Flow<String?>): Flow<Boolean> = field(serverUrl) { it.showThinking }

    suspend fun setShowThinking(origin: String?, value: Boolean) = updatePreferencesFor(origin) { it.copy(showThinking = value) }

    fun showEnded(serverUrl: Flow<String?>): Flow<Boolean> = field(serverUrl) { it.showEndedSessions }

    suspend fun setShowEnded(origin: String?, value: Boolean) = updatePreferencesFor(origin) { it.copy(showEndedSessions = value) }

    /** Starred project folders of the server at [serverUrl], in pin order (index caps and switch shortcuts key off position). */
    fun pinnedProjects(serverUrl: Flow<String?>): Flow<List<String>> = field(serverUrl) { it.pinnedProjects }

    suspend fun setPinnedProjects(origin: String?, projects: List<String>) = updatePreferencesFor(origin) { it.copy(pinnedProjects = projects) }

    /**
     * ta-coik.52: the Overview's filter choice the server at [origin] last had (overview.tsx 90fbb9f
     * :43-55 `readChoice` on the per-origin `tether:overviewFilters`; read when the Overview opens).
     * Fail-soft: unreadable storage or a corrupt record reads as the default choice.
     */
    suspend fun overviewFilters(origin: String?): OverviewFilters = runCatching {
        overviewFiltersJson(data.first()[Keys.overviewFiltersByOriginJson])[origin.orEmpty()]
    }.getOrNull() ?: OverviewFilters()

    /** overview.tsx :56-62 `writeChoice`, on every change; a refused write only costs persistence. */
    suspend fun setOverviewFilters(origin: String?, filters: OverviewFilters) {
        runCatching {
            save { prefs ->
                val all = overviewFiltersJson(prefs[Keys.overviewFiltersByOriginJson]) + (origin.orEmpty() to filters)
                prefs[Keys.overviewFiltersByOriginJson] = buildJsonObject { for ((o, f) in all) put(o, f.toJson()) }.toString()
            }
        }
    }

    companion object {
        /** T15.4: preferences on a store of the caller's own (a shell test's, so its boot view's inputs are its own). */
        fun on(store: DataStore<Preferences>): UiPrefs = UiPrefs(store)

        private val keptByStore = WeakHashMap<DataStore<Preferences>, MutableStateFlow<Preferences?>>()

        /** ta-coik.47: `{ origin: view }`, fail-soft: unreadable JSON is no record, a non-string view is dropped. */
        private fun viewsJson(value: String?): Map<String, String> {
            val root = value?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
            return root.entries.mapNotNull { (o, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { o to it.content } }.toMap()
        }

        /** ta-coik.52: `{ origin: { workspace, provider, status } }`; unreadable JSON is no record. */
        private fun overviewFiltersJson(value: String?): Map<String, OverviewFilters> {
            val root = value?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyMap()
            return root.mapValues { (_, v) -> OverviewFilters.fromJson(v) }
        }

        private fun parse(prefs: Preferences): TetherPreferences =
            TetherPreferences.parse(prefs.asMap().entries.associate { (key, value) -> key.name to value })

        /** Every model field is written back, like the web's whole-object save. */
        private fun write(prefs: MutablePreferences, next: TetherPreferences) {
            // Like the web (lib/theme-mode.mjs DEPRECATED_THEME_KEYS), the retired family and the
            // legacy flat id are consumed by the read and never saved again.
            prefs.remove(Keys.theme)
            prefs.remove(Keys.themeFamily)
            // ta-coik.52: every server's own record, as JSON; the device-wide keys of before only while a
            // device-wide value (one that is not the web default) waits for the migration.
            prefs.putOrRemove(Keys.preferencesByOriginJson, next.preferencesByOrigin.takeIf { it.isNotEmpty() }?.let(TetherPreferences::joinPreferencesByOrigin))
            if (ServerPreferences.of(next) == ServerPreferences.Default) {
                Keys.deviceWide.forEach { prefs.remove(it) }
                return writeServerRecords(prefs, next)
            }
            prefs[Keys.themeMode] = next.themeMode.id
            prefs[Keys.loginVariant] = next.loginVariant.id
            prefs[Keys.defaultWorkspace] = next.defaultWorkspace
            prefs[Keys.showEnded] = next.showEndedSessions
            prefs[Keys.confirmBeforeEnd] = next.confirmBeforeEnd
            prefs[Keys.showThinking] = next.showThinking
            prefs[Keys.sidebarCollapsed] = next.sidebarCollapsed
            prefs.putOrRemove(Keys.sidebarWidth, next.sidebarWidth)
            prefs.putOrRemove(Keys.inspectorWidth, next.inspectorWidth)
            prefs[Keys.pinnedProjects] = TetherPreferences.joinLines(next.pinnedProjects)
            prefs[Keys.sidebarActiveOnly] = next.sidebarActiveOnly
            prefs[Keys.sidebarUnreadOnly] = next.sidebarUnreadOnly
            prefs[Keys.sidebarHideAgentRuns] = next.sidebarHideAgentRuns
            prefs[Keys.sidebarSort] = next.sidebarSort.id
            prefs[Keys.pinnedModels] = TetherPreferences.joinLines(next.pinnedModels)
            writeServerRecords(prefs, next)
        }

        private fun writeServerRecords(prefs: MutablePreferences, next: TetherPreferences) {
            // ta-coik.47: per server origin as JSON; the device-wide keys of before only until migrated.
            prefs.putOrRemove(Keys.collapsedWorkspaces, TetherPreferences.joinLines(next.collapsedWorkspaces).takeIf { it.isNotEmpty() })
            prefs.putOrRemove(Keys.lastSeenSessions, TetherPreferences.joinSeen(next.lastSeenSessions).takeIf { it.isNotEmpty() })
            prefs.putOrRemove(Keys.collapsedByOriginJson, next.collapsedByOrigin.takeIf { it.isNotEmpty() }?.let(TetherPreferences::joinCollapsed))
            prefs.putOrRemove(Keys.lastSeenByOriginJson, next.lastSeenByOrigin.takeIf { it.isNotEmpty() }?.let(TetherPreferences::joinSeenByOrigin))
            val opened = next.lastOpenedSession
            prefs.putOrRemove(Keys.lastOpenedCwd, opened?.cwd)
            prefs.putOrRemove(Keys.lastOpenedSessionId, opened?.sessionId)
            prefs.putOrRemove(Keys.lastOpenedHistoryId, opened?.historyId)
            // ta-coik.46: written as JSON; the tab-line key it replaces is consumed by the read and dropped.
            prefs.remove(Keys.lastOpenedByOrigin)
            prefs.putOrRemove(Keys.lastOpenedByOriginJson, next.lastOpenedByOrigin.takeIf { it.isNotEmpty() }?.let(TetherPreferences::joinOpened))
        }

        private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
            if (value == null) remove(key) else this[key] = value
        }
    }

    // ── Top-level view (T15.4) ────────────────────────────────────────────

    /**
     * What a boot of the console on the server at [origin] resolves its view from (dashboard.tsx
     * `bootView`): that server's remembered last view (ta-coik.47: the web's localStorage is per
     * origin; the device-wide one of before until a write moves it), and whether this install already
     * kept Tether preferences before that record existed (the web tests its preferences key; here the
     * model's stored theme mode, written by every save of the model, or a retired theme key an older
     * version wrote) while no server has a view record yet: once one has, a server without one is a
     * fresh origin, as a browser's never-visited origin is. ta-coik.52: and a server with a
     * preference record of its own ([TetherPreferences.hasRecordFor]) is an existing origin. Unreadable
     * storage reads as a fresh install, like the web's blocked localStorage.
     */
    suspend fun viewBoot(origin: String?): ViewBoot = runCatching {
        val stored = data.first()
        val views = viewsJson(stored[Keys.lastViewByOrigin])
        val deviceWide = Keys.deviceWide.any { it in stored } || Keys.themeFamily in stored || Keys.theme in stored
        ViewBoot(
            storedView = views[origin.orEmpty()] ?: stored[Keys.lastView],
            hasExistingPreferences = parse(stored).hasRecordFor(origin) || (views.isEmpty() && deviceWide),
        )
    }.getOrElse { ViewBoot(storedView = null, hasExistingPreferences = false) }

    /**
     * Remember the top-level view on screen for the server at [origin] (dashboard.tsx
     * `writeStoredView`); the device-wide record of before is dropped (it was read for this boot).
     */
    suspend fun setLastView(origin: String?, view: String) {
        runCatching {
            save { prefs ->
                val views = viewsJson(prefs[Keys.lastViewByOrigin]) + (origin.orEmpty() to view)
                prefs[Keys.lastViewByOrigin] = buildJsonObject { for ((o, v) in views) put(o, JsonPrimitive(v)) }.toString()
                prefs.remove(Keys.lastView)
            }
        }
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
