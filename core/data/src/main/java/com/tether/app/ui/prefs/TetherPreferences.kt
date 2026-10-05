package com.tether.app.ui.prefs

import com.tether.app.protocol.helpers.PanelWidths
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.theme.ThemeMigration
import com.tether.app.ui.theme.ThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * T2.3: the web's `tether.preferences.v1` object (tether hooks/use-preferences.ts:148-212,
 * defaults :237-260), for every field the native app can honour.
 *
 * Deliberately NOT mirrored (see [TetherPreferences.BROWSER_ONLY]): `terminalFontSize`,
 * `scrollback` and `showQuickKeys` are declared and defaulted on the web but read by nothing
 * (the xterm terminal they configured is gone), so there is no behaviour to match.
 *
 * Persistence (ta-coik.52): the web keeps this object in per-origin localStorage, so every field is
 * per server here too, keyed by the canonical server origin (`serverOrigin`; "" = no server
 * configured): [preferencesByOrigin] ([ServerPreferences], the object's own JSON shape),
 * [collapsedByOrigin], [lastSeenByOrigin] and [lastOpenedByOrigin]. The one-key-per-field layout the
 * app shipped before ([PreferenceKeys]) is the device-wide record of before: read through until
 * [migrateToServer] moves it to the first server known (a server's own record wins), then removed.
 * With no server configured (a sign-in screen before any server is set, or after the server is
 * forgotten) a screen reads the "" record, else the device-wide values not yet migrated, else the web
 * defaults; a write then goes to the "" record and migrates nothing. The appearance is migrated onto
 * the Studio mode (T15.5, [ThemeMigration]). Parsing ([parse]) is fail-soft per field: a
 * missing, wrongly-typed or out-of-vocabulary value reads as that field's web default and
 * never throws. The web itself only repairs the theme mode, `sidebarSort`, `loginVariant` and
 * the two panel widths (use-preferences.ts:331-343) and otherwise spreads stored junk through
 * `{...defaults, ...stored}`; a typed store cannot carry junk, so the native rule is the
 * stricter "wrong type ⇒ default".
 */
data class TetherPreferences(
    /**
     * `themeMode`: Studio light, dark or follow system — the only appearance choice. Retired
     * `theme_family` / flat `theme_choice` values are decoded on read ([ThemeMigration]).
     */
    val themeMode: ThemeMode = ThemeMode.Default,
    val loginVariant: LoginVariant = LoginVariant.Default,
    /** The folder new sessions open in when the operator has not picked one ("" = server default). */
    val defaultWorkspace: String = "",
    val showEndedSessions: Boolean = true,
    val confirmBeforeEnd: Boolean = true,
    /** Opt-in on the web: the model's thinking is collapsed away by default. */
    val showThinking: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    /** Dragged rail width in px, or null for the theme default (lib/panel-widths.mjs:66). */
    val sidebarWidth: Int? = null,
    val inspectorWidth: Int? = null,
    val pinnedProjects: List<String> = emptyList(),
    /**
     * The folded workspace blocks. In the stored model: the device-wide list the app kept before
     * ta-coik.47, read only until it is migrated to the current server ([migrateToServer]); in a
     * [forServer] view: that server's list.
     */
    val collapsedWorkspaces: List<String> = emptyList(),
    /**
     * historyId → last time this device opened the conversation (epoch ms). Stored: the device-wide
     * map of before ta-coik.47 (read only until migrated); in a [forServer] view: that server's.
     */
    val lastSeenSessions: Map<String, Long> = emptyMap(),
    /**
     * The single remembered chat the app kept before ta-coik.41 r2. Read only until it is migrated to
     * the current server ([migrateLastOpened]); [lastOpenedFor] falls back to it meanwhile.
     */
    val lastOpenedSession: LastOpenedSession? = null,
    /**
     * ta-coik.41 r2: the web's `lastOpenedSession` per server origin (the web's localStorage is per
     * origin, so each server remembers its own chat). Key "" holds it with no server configured.
     */
    val lastOpenedByOrigin: Map<String, LastOpenedSession> = emptyMap(),
    /** ta-coik.47: the web's `collapsedWorkspaces` per server origin (key "": no server configured). */
    val collapsedByOrigin: Map<String, List<String>> = emptyMap(),
    /** ta-coik.47: the web's `lastSeenSessions` per server origin (key "": no server configured). */
    val lastSeenByOrigin: Map<String, Map<String, Long>> = emptyMap(),
    /**
     * ta-coik.52: every other field of the web's `tether.preferences.v1` per server origin (the web's
     * localStorage is per origin; key "": no server configured). In the stored model the top-level
     * fields of [ServerPreferences] are the device-wide values of before, read only until they are
     * migrated to the current server ([migrateToServer]); in a [forServer] view: that server's.
     */
    val preferencesByOrigin: Map<String, ServerPreferences> = emptyMap(),
    val sidebarActiveOnly: Boolean = false,
    val sidebarUnreadOnly: Boolean = false,
    val sidebarHideAgentRuns: Boolean = true,
    val sidebarSort: SidebarSort = SidebarSort.Created,
    val pinnedModels: List<String> = emptyList(),
) {
    /** The chat [origin]'s server last had on screen (null origin: no server configured). */
    fun lastOpenedFor(origin: String?): LastOpenedSession? = lastOpenedByOrigin[origin.orEmpty()] ?: lastOpenedSession

    /** dashboard.tsx 90fbb9f :829-835 for [origin]: written only when the cwd or the id differs. */
    fun rememberOpenedFor(origin: String?, opened: LastOpenedSession): TetherPreferences {
        val current = lastOpenedByOrigin[origin.orEmpty()]
        if (current != null && current.cwd == opened.cwd && current.sessionId == opened.sessionId) return this
        return copy(lastOpenedByOrigin = lastOpenedByOrigin + (origin.orEmpty() to opened))
    }

    /** The pre-r2 single value becomes [origin]'s (once: it is cleared), unless that server has its own. */
    fun migrateLastOpened(origin: String): TetherPreferences {
        val legacy = lastOpenedSession ?: return this
        val byOrigin = if (origin in lastOpenedByOrigin) lastOpenedByOrigin else lastOpenedByOrigin + (origin to legacy)
        return copy(lastOpenedSession = null, lastOpenedByOrigin = byOrigin)
    }

    /**
     * ta-coik.47: a device-wide value of before is still waiting for [migrateToServer]; ta-coik.52: the
     * device-wide [ServerPreferences] too, while any of them differs from the web default.
     */
    val hasDeviceWideServerRecords: Boolean
        get() = lastOpenedSession != null || collapsedWorkspaces.isNotEmpty() || lastSeenSessions.isNotEmpty() ||
            ServerPreferences.of(this) != ServerPreferences.Default

    /**
     * ta-coik.47: every device-wide value of before (the remembered chat, the folded blocks, the
     * seen stamps; ta-coik.52: and every other preference) becomes [origin]'s, once: each is cleared
     * (back to the web default), and a server's own record wins.
     */
    fun migrateToServer(origin: String): TetherPreferences {
        if (!hasDeviceWideServerRecords) return this
        val opened = migrateLastOpened(origin)
        val general = ServerPreferences.of(this)
        return ServerPreferences.Default.applyTo(
            opened.copy(
                collapsedWorkspaces = emptyList(),
                lastSeenSessions = emptyMap(),
                collapsedByOrigin = if (collapsedWorkspaces.isEmpty() || origin in collapsedByOrigin) collapsedByOrigin else collapsedByOrigin + (origin to collapsedWorkspaces),
                lastSeenByOrigin = if (lastSeenSessions.isEmpty() || origin in lastSeenByOrigin) lastSeenByOrigin else lastSeenByOrigin + (origin to lastSeenSessions),
                preferencesByOrigin = if (general == ServerPreferences.Default || origin in preferencesByOrigin) preferencesByOrigin else preferencesByOrigin + (origin to general),
            ),
        )
    }

    /**
     * ta-coik.47: the model as the server at [origin] sees it (the web's localStorage is per origin):
     * [collapsedWorkspaces], [lastSeenSessions] and [lastOpenedSession] are that server's (the
     * device-wide value of before until it is migrated); ta-coik.52: and so is every
     * [ServerPreferences] field. Write it back with [updateForServer].
     */
    fun forServer(origin: String?): TetherPreferences = (preferencesByOrigin[origin.orEmpty()] ?: ServerPreferences.of(this)).applyTo(
        copy(
            collapsedWorkspaces = collapsedByOrigin[origin.orEmpty()] ?: collapsedWorkspaces,
            lastSeenSessions = lastSeenByOrigin[origin.orEmpty()] ?: lastSeenSessions,
            lastOpenedSession = lastOpenedFor(origin),
        ),
    )

    /**
     * ta-coik.52: the server at [origin] already kept preferences of its own (the web's
     * `localStorage.getItem("tether.preferences.v1") !== null` on that origin).
     */
    fun hasRecordFor(origin: String?): Boolean = origin.orEmpty().let {
        it in preferencesByOrigin || it in collapsedByOrigin || it in lastSeenByOrigin || it in lastOpenedByOrigin
    }

    /**
     * ta-coik.47: [transform] a [forServer] view and store it: the per-server fields go to [origin]'s
     * records (the device-wide values of before migrate there first; ta-coik.52: every preference
     * is a per-server field).
     */
    fun updateForServer(origin: String?, transform: (TetherPreferences) -> TetherPreferences): TetherPreferences {
        val key = origin.orEmpty()
        val base = if (origin != null) migrateToServer(origin) else this
        val scoped = base.forServer(origin)
        val next = transform(scoped)
        val opened = next.lastOpenedSession?.takeIf { it != scoped.lastOpenedSession }
        return ServerPreferences.of(base).applyTo(next).copy(
            collapsedWorkspaces = base.collapsedWorkspaces,
            lastSeenSessions = base.lastSeenSessions,
            lastOpenedSession = base.lastOpenedSession,
            collapsedByOrigin = base.collapsedByOrigin + (key to next.collapsedWorkspaces),
            lastSeenByOrigin = base.lastSeenByOrigin + (key to next.lastSeenSessions),
            lastOpenedByOrigin = if (opened != null) base.lastOpenedByOrigin + (key to opened) else base.lastOpenedByOrigin,
            preferencesByOrigin = base.preferencesByOrigin + (key to ServerPreferences.of(next)),
        )
    }

    companion object {
        /** The web `defaults` (use-preferences.ts:237-260). */
        val Default = TetherPreferences()

        /** Web preference fields intentionally not mirrored, with the reason. */
        val BROWSER_ONLY: Map<String, String> = linkedMapOf(
            "terminalFontSize" to "declared/defaulted (13) but read by nothing on the web: the xterm view it sized is gone",
            "scrollback" to "declared/defaulted (10000) but read by nothing on the web: xterm-era terminal buffer",
            "showQuickKeys" to "declared/defaulted (true) but read by nothing on the web: xterm-era on-screen key row",
        )

        /**
         * Fail-soft read of the stored fields, keyed by DataStore key name ([PreferenceKeys]).
         * Values are whatever DataStore holds (Boolean/Int/Long/Float/Double/String); anything
         * unusable falls back to that field's default.
         */
        fun parse(raw: Map<String, Any?>): TetherPreferences {
            val d = Default
            fun bool(key: String, default: Boolean) = raw[key] as? Boolean ?: default
            fun str(key: String) = raw[key] as? String
            return TetherPreferences(
                // lib/theme-mode.mjs normalizeThemeMode (T15.5): the stored family is ignored.
                themeMode = ThemeMigration.normalize(
                    str(PreferenceKeys.THEME_MODE),
                    str(PreferenceKeys.LEGACY_THEME),
                ),
                // lib/theme-mode.mjs normalizeLoginVariant — anything but the explicit opt-in is Default.
                loginVariant = LoginVariant.fromId(str(PreferenceKeys.LOGIN_VARIANT)),
                defaultWorkspace = str(PreferenceKeys.DEFAULT_WORKSPACE) ?: d.defaultWorkspace,
                showEndedSessions = bool(PreferenceKeys.SHOW_ENDED_SESSIONS, d.showEndedSessions),
                confirmBeforeEnd = bool(PreferenceKeys.CONFIRM_BEFORE_END, d.confirmBeforeEnd),
                showThinking = bool(PreferenceKeys.SHOW_THINKING, d.showThinking),
                sidebarCollapsed = bool(PreferenceKeys.SIDEBAR_COLLAPSED, d.sidebarCollapsed),
                // use-preferences.ts:342-343 — the ported lib/panel-widths.mjs parse.
                sidebarWidth = panelWidth(raw[PreferenceKeys.SIDEBAR_WIDTH]),
                inspectorWidth = panelWidth(raw[PreferenceKeys.INSPECTOR_WIDTH]),
                pinnedProjects = lines(str(PreferenceKeys.PINNED_PROJECTS)),
                collapsedWorkspaces = lines(str(PreferenceKeys.COLLAPSED_WORKSPACES)),
                lastSeenSessions = seenMap(str(PreferenceKeys.LAST_SEEN_SESSIONS)),
                lastOpenedSession = lastOpened(
                    str(PreferenceKeys.LAST_OPENED_CWD),
                    str(PreferenceKeys.LAST_OPENED_SESSION_ID),
                    raw[PreferenceKeys.LAST_OPENED_HISTORY_ID],
                ),
                // ta-coik.46: the JSON record; the tab-line one of ta-coik.41 r2 only until it is rewritten.
                lastOpenedByOrigin = str(PreferenceKeys.LAST_OPENED_BY_ORIGIN_JSON)?.let(::openedJson)
                    ?: legacyOpenedMap(str(PreferenceKeys.LAST_OPENED_BY_ORIGIN)),
                collapsedByOrigin = str(PreferenceKeys.COLLAPSED_BY_ORIGIN_JSON)?.let(::collapsedJson).orEmpty(),
                lastSeenByOrigin = str(PreferenceKeys.LAST_SEEN_BY_ORIGIN_JSON)?.let(::seenJson).orEmpty(),
                preferencesByOrigin = str(PreferenceKeys.PREFERENCES_BY_ORIGIN_JSON)?.let(::preferencesJson).orEmpty(),
                sidebarActiveOnly = bool(PreferenceKeys.SIDEBAR_ACTIVE_ONLY, d.sidebarActiveOnly),
                sidebarUnreadOnly = bool(PreferenceKeys.SIDEBAR_UNREAD_ONLY, d.sidebarUnreadOnly),
                sidebarHideAgentRuns = bool(PreferenceKeys.SIDEBAR_HIDE_AGENT_RUNS, d.sidebarHideAgentRuns),
                // use-preferences.ts:338 — only the explicit "last-active" is honoured.
                sidebarSort = SidebarSort.fromId(str(PreferenceKeys.SIDEBAR_SORT)),
                pinnedModels = lines(str(PreferenceKeys.PINNED_MODELS)),
            )
        }

        private fun panelWidth(value: Any?): Int? {
            val js: JsValue = when (value) {
                is Number -> JsNum(value.toDouble())
                is String -> JsStr(value)
                else -> return null
            }
            return PanelWidths.parseStoredPanelWidth(js)?.toInt()
        }

        /** Newline-joined ordered list (the shipped `pinned_projects` layout). */
        internal fun lines(value: String?): List<String> = value?.split('\n')?.filter(String::isNotBlank) ?: emptyList()

        internal fun joinLines(values: List<String>): String = values.filter { it.isNotBlank() && '\n' !in it }.joinToString("\n")

        /** `historyId\tepochMs` lines; a line without a finite non-negative number is dropped. */
        private fun seenMap(value: String?): Map<String, Long> {
            if (value.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, Long>()
            for (line in value.split('\n')) {
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) continue
                val at = line.substring(tab + 1).toLongOrNull() ?: continue
                if (at >= 0) out[line.substring(0, tab)] = at
            }
            return out
        }

        internal fun joinSeen(values: Map<String, Long>): String = values.entries
            .filter { (id, _) -> id.isNotEmpty() && '\n' !in id && '\t' !in id }
            .joinToString("\n") { (id, at) -> "$id\t$at" }

        /**
         * The ta-coik.41 r2 layout, read only to migrate: `origin\tsessionId\thistoryId\tcwd` lines
         * (historyId "" = null; the cwd last, so it may hold a tab). A line without a session id or a
         * cwd is dropped.
         */
        private fun legacyOpenedMap(value: String?): Map<String, LastOpenedSession> {
            if (value.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, LastOpenedSession>()
            for (line in value.split('\n')) {
                val parts = line.split('\t', limit = 4)
                if (parts.size != 4 || parts[1].isEmpty()) continue
                out[parts[0]] = LastOpenedSession(parts[3], parts[1], parts[2].ifEmpty { null })
            }
            return out
        }

        /**
         * ta-coik.46: `{ origin: { cwd, sessionId, historyId } }`, the web's own record shape per origin
         * (use-preferences.ts `lastOpenedSession`), so any character in any field (a newline in a folder
         * name included) round-trips. Fail-soft: unreadable JSON is no record, and an entry without a
         * string cwd and a non-empty string sessionId is dropped (historyId: a non-empty string or null).
         */
        private fun openedJson(value: String): Map<String, LastOpenedSession> {
            val root = runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, LastOpenedSession>()
            for ((origin, entry) in root) {
                val o = entry as? JsonObject ?: continue
                val cwd = (o["cwd"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val sessionId = (o["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (sessionId.isNullOrEmpty()) continue
                val historyId = (o["historyId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
                out[origin] = LastOpenedSession(cwd, sessionId, historyId)
            }
            return out
        }

        private fun jsonObject(value: String): JsonObject? = runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonObject

        /**
         * ta-coik.52: `{ origin: { themeMode, loginVariant, … } }`, the web's own `tether.preferences.v1`
         * object per origin. Fail-soft: unreadable JSON is no record, an origin whose value is not an
         * object is dropped, and a field that is missing or unusable reads as its web default.
         */
        private fun preferencesJson(value: String): Map<String, ServerPreferences> {
            val root = jsonObject(value) ?: return emptyMap()
            val out = LinkedHashMap<String, ServerPreferences>()
            for ((origin, entry) in root) out[origin] = ServerPreferences.fromJson(entry) ?: continue
            return out
        }

        internal fun joinPreferencesByOrigin(values: Map<String, ServerPreferences>): String = buildJsonObject {
            for ((origin, p) in values) put(origin, p.toJson())
        }.toString()

        /**
         * ta-coik.47: `{ origin: [cwd, …] }`, the web's `collapsedWorkspaces` per origin. Fail-soft:
         * unreadable JSON is no record, an origin whose value is not an array is dropped, and so is
         * an entry that is not a non-empty string.
         */
        private fun collapsedJson(value: String): Map<String, List<String>> {
            val root = jsonObject(value) ?: return emptyMap()
            val out = LinkedHashMap<String, List<String>>()
            for ((origin, entry) in root) {
                val list = entry as? JsonArray ?: continue
                out[origin] = list.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }
            }
            return out
        }

        internal fun joinCollapsed(values: Map<String, List<String>>): String = buildJsonObject {
            for ((origin, list) in values) put(origin, JsonArray(list.filter(String::isNotEmpty).map(::JsonPrimitive)))
        }.toString()

        /**
         * ta-coik.47: `{ origin: { historyId: epochMs } }`, the web's `lastSeenSessions` per origin.
         * Fail-soft: unreadable JSON is no record, an origin whose value is not an object is dropped,
         * and so is a stamp that is not a finite non-negative number (or has an empty id).
         */
        private fun seenJson(value: String): Map<String, Map<String, Long>> {
            val root = jsonObject(value) ?: return emptyMap()
            val out = LinkedHashMap<String, Map<String, Long>>()
            for ((origin, entry) in root) {
                val stamps = entry as? JsonObject ?: continue
                val seen = LinkedHashMap<String, Long>()
                for ((id, at) in stamps) {
                    val p = at as? JsonPrimitive ?: continue
                    if (p.isString || id.isEmpty()) continue
                    val ms = p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.toLong() ?: continue
                    if (ms >= 0) seen[id] = ms
                }
                out[origin] = seen
            }
            return out
        }

        internal fun joinSeenByOrigin(values: Map<String, Map<String, Long>>): String = buildJsonObject {
            for ((origin, seen) in values) {
                put(origin, buildJsonObject { for ((id, at) in seen) if (id.isNotEmpty() && at >= 0) put(id, JsonPrimitive(at)) })
            }
        }.toString()

        internal fun joinOpened(values: Map<String, LastOpenedSession>): String = buildJsonObject {
            for ((origin, o) in values) {
                if (o.sessionId.isEmpty()) continue
                put(
                    origin,
                    buildJsonObject {
                        put("cwd", JsonPrimitive(o.cwd))
                        put("sessionId", JsonPrimitive(o.sessionId))
                        put("historyId", o.historyId?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            }
        }.toString()

        /** The web's `{ cwd, sessionId, historyId }`: both ids required, historyId string-or-null. */
        private fun lastOpened(cwd: String?, sessionId: String?, historyId: Any?): LastOpenedSession? {
            if (cwd == null || sessionId.isNullOrEmpty()) return null
            return LastOpenedSession(cwd, sessionId, (historyId as? String)?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * ta-coik.52: the fields of the web's `tether.preferences.v1` object that the app kept device-wide until
 * then, as one server's record (the web's localStorage is per origin, so each server has its own).
 * Stored as that object's own JSON shape; read fail-soft per field, as [TetherPreferences.parse].
 */
data class ServerPreferences(
    val themeMode: ThemeMode = ThemeMode.Default,
    val loginVariant: LoginVariant = LoginVariant.Default,
    val defaultWorkspace: String = "",
    val showEndedSessions: Boolean = true,
    val confirmBeforeEnd: Boolean = true,
    val showThinking: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val sidebarWidth: Int? = null,
    val inspectorWidth: Int? = null,
    val pinnedProjects: List<String> = emptyList(),
    val sidebarActiveOnly: Boolean = false,
    val sidebarUnreadOnly: Boolean = false,
    val sidebarHideAgentRuns: Boolean = true,
    val sidebarSort: SidebarSort = SidebarSort.Created,
    val pinnedModels: List<String> = emptyList(),
) {
    /** These values as [p]'s fields. */
    fun applyTo(p: TetherPreferences): TetherPreferences = p.copy(
        themeMode = themeMode,
        loginVariant = loginVariant,
        defaultWorkspace = defaultWorkspace,
        showEndedSessions = showEndedSessions,
        confirmBeforeEnd = confirmBeforeEnd,
        showThinking = showThinking,
        sidebarCollapsed = sidebarCollapsed,
        sidebarWidth = sidebarWidth,
        inspectorWidth = inspectorWidth,
        pinnedProjects = pinnedProjects,
        sidebarActiveOnly = sidebarActiveOnly,
        sidebarUnreadOnly = sidebarUnreadOnly,
        sidebarHideAgentRuns = sidebarHideAgentRuns,
        sidebarSort = sidebarSort,
        pinnedModels = pinnedModels,
    )

    /** The web's field names and value types (use-preferences.ts `TetherPreferences`). */
    fun toJson(): JsonObject = buildJsonObject {
        put("themeMode", JsonPrimitive(themeMode.id))
        put("loginVariant", JsonPrimitive(loginVariant.id))
        put("defaultWorkspace", JsonPrimitive(defaultWorkspace))
        put("showEndedSessions", JsonPrimitive(showEndedSessions))
        put("confirmBeforeEnd", JsonPrimitive(confirmBeforeEnd))
        put("showThinking", JsonPrimitive(showThinking))
        put("sidebarCollapsed", JsonPrimitive(sidebarCollapsed))
        put("sidebarWidth", sidebarWidth?.let(::JsonPrimitive) ?: JsonNull)
        put("inspectorWidth", inspectorWidth?.let(::JsonPrimitive) ?: JsonNull)
        put("pinnedProjects", JsonArray(pinnedProjects.filter(String::isNotEmpty).map(::JsonPrimitive)))
        put("sidebarActiveOnly", JsonPrimitive(sidebarActiveOnly))
        put("sidebarUnreadOnly", JsonPrimitive(sidebarUnreadOnly))
        put("sidebarHideAgentRuns", JsonPrimitive(sidebarHideAgentRuns))
        put("sidebarSort", JsonPrimitive(sidebarSort.id))
        put("pinnedModels", JsonArray(pinnedModels.filter(String::isNotEmpty).map(::JsonPrimitive)))
    }

    companion object {
        val Default = ServerPreferences()

        fun of(p: TetherPreferences): ServerPreferences = ServerPreferences(
            themeMode = p.themeMode,
            loginVariant = p.loginVariant,
            defaultWorkspace = p.defaultWorkspace,
            showEndedSessions = p.showEndedSessions,
            confirmBeforeEnd = p.confirmBeforeEnd,
            showThinking = p.showThinking,
            sidebarCollapsed = p.sidebarCollapsed,
            sidebarWidth = p.sidebarWidth,
            inspectorWidth = p.inspectorWidth,
            pinnedProjects = p.pinnedProjects,
            sidebarActiveOnly = p.sidebarActiveOnly,
            sidebarUnreadOnly = p.sidebarUnreadOnly,
            sidebarHideAgentRuns = p.sidebarHideAgentRuns,
            sidebarSort = p.sidebarSort,
            pinnedModels = p.pinnedModels,
        )

        /**
         * One origin's record, fail-soft per field (a missing, wrongly-typed or out-of-vocabulary value
         * reads as that field's web default; the widths through the ported lib/panel-widths.mjs parse);
         * null when [element] is not an object.
         */
        fun fromJson(element: JsonElement?): ServerPreferences? {
            val o = element as? JsonObject ?: return null
            val d = Default
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun bool(key: String, default: Boolean) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
            fun list(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }.orEmpty()
            fun width(key: String): Int? {
                val p = o[key] as? JsonPrimitive ?: return null
                val js: JsValue = if (p.isString) JsStr(p.content) else JsNum(p.doubleOrNull ?: return null)
                return PanelWidths.parseStoredPanelWidth(js)?.toInt()
            }
            return ServerPreferences(
                themeMode = ThemeMigration.normalize(str("themeMode"), null),
                loginVariant = LoginVariant.fromId(str("loginVariant")),
                defaultWorkspace = str("defaultWorkspace") ?: d.defaultWorkspace,
                showEndedSessions = bool("showEndedSessions", d.showEndedSessions),
                confirmBeforeEnd = bool("confirmBeforeEnd", d.confirmBeforeEnd),
                showThinking = bool("showThinking", d.showThinking),
                sidebarCollapsed = bool("sidebarCollapsed", d.sidebarCollapsed),
                sidebarWidth = width("sidebarWidth"),
                inspectorWidth = width("inspectorWidth"),
                pinnedProjects = list("pinnedProjects"),
                sidebarActiveOnly = bool("sidebarActiveOnly", d.sidebarActiveOnly),
                sidebarUnreadOnly = bool("sidebarUnreadOnly", d.sidebarUnreadOnly),
                sidebarHideAgentRuns = bool("sidebarHideAgentRuns", d.sidebarHideAgentRuns),
                sidebarSort = SidebarSort.fromId(str("sidebarSort")),
                pinnedModels = list("pinnedModels"),
            )
        }
    }
}

/**
 * ta-coik.52: the web's `tether:overviewFilters` (overview.tsx 90fbb9f :35-62 `OverviewFilterChoice`),
 * per server origin. [status] is the tab's key ("active", "waiting", …); the shell maps it.
 */
data class OverviewFilters(val workspace: String? = null, val provider: String? = null, val status: String = "active") {
    fun toJson(): JsonObject = buildJsonObject {
        put("workspace", workspace?.let(::JsonPrimitive) ?: JsonNull)
        put("provider", provider?.let(::JsonPrimitive) ?: JsonNull)
        put("status", JsonPrimitive(status))
    }

    companion object {
        /** overview.tsx :43-55 `readChoice`: an empty or non-string scope is none, a non-string status "active". */
        fun fromJson(element: JsonElement?): OverviewFilters {
            val o = element as? JsonObject ?: return OverviewFilters()
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return OverviewFilters(
                workspace = str("workspace")?.takeIf(String::isNotEmpty),
                provider = str("provider")?.takeIf(String::isNotEmpty),
                status = str("status") ?: "active",
            )
        }
    }
}

/** The web's `lastOpenedSession` (use-preferences.ts:190): restored on boot. */
data class LastOpenedSession(val cwd: String, val sessionId: String, val historyId: String?)

/** The web's `sidebarSort` (use-preferences.ts:205). */
enum class SidebarSort(val id: String) {
    Created("created"),
    LastActive("last-active"),
    ;

    companion object {
        fun fromId(id: String?): SidebarSort = if (id == LastActive.id) LastActive else Created
    }
}

/**
 * Stable DataStore key names (file `tether_ui_prefs`), web field → key. The first seven were
 * already shipped before T2.3 and must never be renamed.
 */
object PreferenceKeys {
    /** Deprecated: the flat theme id (up to 0.6.x), web `theme`; read only to migrate, dropped on save. */
    const val LEGACY_THEME = "theme_choice"
    /** Deprecated: the retired theme family (0.7.x to 0.8.0), web `themeFamily`; dropped on save. */
    const val THEME_FAMILY = "theme_family"
    const val THEME_MODE = "theme_mode"
    const val SHOW_THINKING = "show_thinking"
    const val SHOW_ENDED_SESSIONS = "show_ended_sessions"
    const val PINNED_PROJECTS = "pinned_projects"
    const val LOGIN_VARIANT = "login_variant"

    const val DEFAULT_WORKSPACE = "default_workspace"
    const val CONFIRM_BEFORE_END = "confirm_before_end"
    const val SIDEBAR_COLLAPSED = "sidebar_collapsed"
    const val SIDEBAR_WIDTH = "sidebar_width"
    const val INSPECTOR_WIDTH = "inspector_width"
    const val COLLAPSED_WORKSPACES = "collapsed_workspaces"
    const val LAST_SEEN_SESSIONS = "last_seen_sessions"
    const val LAST_OPENED_CWD = "last_opened_cwd"
    const val LAST_OPENED_SESSION_ID = "last_opened_session_id"
    const val LAST_OPENED_HISTORY_ID = "last_opened_history_id"
    /** ta-coik.41 r2: native-only, `lastOpenedSession` per server origin (tab lines; read only to migrate). */
    const val LAST_OPENED_BY_ORIGIN = "last_opened_by_origin"
    /** ta-coik.46: the same record as JSON (any character in any field); replaces [LAST_OPENED_BY_ORIGIN]. */
    const val LAST_OPENED_BY_ORIGIN_JSON = "last_opened_by_origin_json"
    /** ta-coik.47: `collapsedWorkspaces` per server origin, JSON; replaces [COLLAPSED_WORKSPACES] (read only to migrate). */
    const val COLLAPSED_BY_ORIGIN_JSON = "collapsed_workspaces_by_origin_json"
    /** ta-coik.47: `lastSeenSessions` per server origin, JSON; replaces [LAST_SEEN_SESSIONS] (read only to migrate). */
    const val LAST_SEEN_BY_ORIGIN_JSON = "last_seen_by_origin_json"
    /**
     * ta-coik.52: every other `tether.preferences.v1` field per server origin, JSON ([ServerPreferences]);
     * replaces the device-wide keys above (read only to migrate).
     */
    const val PREFERENCES_BY_ORIGIN_JSON = "preferences_by_origin_json"
    /** ta-coik.52: the web's `tether:overviewFilters` per server origin, JSON ([OverviewFilters]). */
    const val OVERVIEW_FILTERS_BY_ORIGIN_JSON = "overview_filters_by_origin_json"
    /** ta-coik.47: the web's `tether:lastView` per server origin, JSON; replaces `last_view` (read only to migrate). */
    const val LAST_VIEW_BY_ORIGIN_JSON = "last_view_by_origin_json"
    const val SIDEBAR_ACTIVE_ONLY = "sidebar_active_only"
    const val SIDEBAR_UNREAD_ONLY = "sidebar_unread_only"
    const val SIDEBAR_HIDE_AGENT_RUNS = "sidebar_hide_agent_runs"
    const val SIDEBAR_SORT = "sidebar_sort"
    const val PINNED_MODELS = "pinned_models"
}
