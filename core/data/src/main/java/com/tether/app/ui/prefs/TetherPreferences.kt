package com.tether.app.ui.prefs

import com.tether.app.protocol.helpers.PanelWidths
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.theme.ThemeChoice

/**
 * T2.3: the web's `tether.preferences.v1` object (tether hooks/use-preferences.ts:148-212,
 * defaults :237-260), for every field the native app can honour.
 *
 * Deliberately NOT mirrored (see [TetherPreferences.BROWSER_ONLY]): `terminalFontSize`,
 * `scrollback` and `showQuickKeys` are declared and defaulted on the web but read by nothing
 * (the xterm terminal they configured is gone), so there is no behaviour to match.
 *
 * Persistence is one DataStore key per field ([PreferenceKeys]) rather than one JSON blob, so
 * the keys the app already shipped (theme/showThinking/showEnded/pinnedProjects/loginVariant)
 * keep their stored values with no migration. Parsing ([parse]) is fail-soft per field: a
 * missing, wrongly-typed or out-of-vocabulary value reads as that field's web default and
 * never throws. The web itself only repairs the theme pair, `sidebarSort`, `loginVariant` and
 * the two panel widths (use-preferences.ts:331-343) and otherwise spreads stored junk through
 * `{...defaults, ...stored}`; a typed store cannot carry junk, so the native rule is the
 * stricter "wrong type ⇒ default".
 */
data class TetherPreferences(
    /** `themeFamily` + `themeMode` (+ the legacy flat `theme`, migrated on read). */
    val theme: ThemeChoice = ThemeChoice.Default,
    val loginVariant: LoginVariant = LoginVariant.Instrument,
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
    val collapsedWorkspaces: List<String> = emptyList(),
    /** historyId → last time this device opened the conversation (epoch ms). */
    val lastSeenSessions: Map<String, Long> = emptyMap(),
    val lastOpenedSession: LastOpenedSession? = null,
    val sidebarActiveOnly: Boolean = false,
    val sidebarUnreadOnly: Boolean = false,
    val sidebarHideAgentRuns: Boolean = true,
    val sidebarSort: SidebarSort = SidebarSort.Created,
    val pinnedModels: List<String> = emptyList(),
) {
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
                // use-preferences.ts:116-129 via ThemeChoice.normalize (T3.1).
                theme = ThemeChoice.normalize(
                    str(PreferenceKeys.THEME_FAMILY),
                    str(PreferenceKeys.THEME_MODE),
                    str(PreferenceKeys.LEGACY_THEME),
                ),
                // use-preferences.ts:340 — anything but the explicit opt-in is Instrument.
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

        /** The web's `{ cwd, sessionId, historyId }`: both ids required, historyId string-or-null. */
        private fun lastOpened(cwd: String?, sessionId: String?, historyId: Any?): LastOpenedSession? {
            if (cwd == null || sessionId.isNullOrEmpty()) return null
            return LastOpenedSession(cwd, sessionId, (historyId as? String)?.takeIf { it.isNotEmpty() })
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
    /** Legacy flat theme id (pre-T3.1), web `theme`; read only to migrate. */
    const val LEGACY_THEME = "theme_choice"
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
    const val SIDEBAR_ACTIVE_ONLY = "sidebar_active_only"
    const val SIDEBAR_UNREAD_ONLY = "sidebar_unread_only"
    const val SIDEBAR_HIDE_AGENT_RUNS = "sidebar_hide_agent_runs"
    const val SIDEBAR_SORT = "sidebar_sort"
    const val PINNED_MODELS = "pinned_models"
}
