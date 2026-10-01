package com.tether.app.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.tether.app.client.ServerSetting
import com.tether.app.ui.components.KeyClasses
import com.tether.app.ui.components.TetherKey
import com.tether.app.ui.icons.TetherIcons
import com.tether.app.ui.prefs.TetherPreferences
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Tags of the panels' controls. */
object SettingsPanelTags {
    const val UseCurrent = "settings-use-current"
    const val DefaultWorkspace = "settings-default-workspace"
    fun toggle(toggle: GeneralToggle) = "settings-toggle-${toggle.name}"
    fun themeMode(mode: ThemeMode) = "settings-theme-${mode.id}"
    fun loginVariant(id: String) = "settings-login-$id"
}

/**
 * One tab's panel (settings-dialog.tsx `settings-panel-<id>`). The shell (SettingsDialog.kt) owns
 * the header, tabs, restart banner and footer; each panel is its own composable below, so a later
 * slice replaces its panel's body without touching the shell.
 */
@Composable
internal fun SettingsPanel(
    tab: SettingsTab,
    prefs: UiPrefs,
    live: TetherPreferences,
    state: SettingsDialogState,
    currentWorkspace: String,
    narrow: Boolean,
    claudeAccounts: ClaudeAccountsBinding = ClaudeAccountsBinding.None,
    serverSettings: ServerSettingsBinding = ServerSettingsBinding.None,
) {
    when (tab) {
        SettingsTab.General -> GeneralPanel(live, state, currentWorkspace, narrow)
        SettingsTab.Appearance -> AppearancePanel(prefs, live, narrow)
        SettingsTab.Devices -> DevicesPanel(prefs, narrow)
        SettingsTab.Nodes -> NodesPanel(narrow)
        SettingsTab.Engines -> EnginesPanel(narrow, claudeAccounts)
        // ta-t7l: keyed on the server, so another server's tab starts from nothing (every secret
        // masked, every half-typed field dropped; a dropped edit is bound to its own server).
        SettingsTab.Metadata -> key(serverSettings.origin) { MetadataPanel(narrow, serverSettings) }
        SettingsTab.Advanced -> key(serverSettings.origin) { AdvancedPanel(narrow, serverSettings) }
    }
}

/**
 * General (settings-dialog.tsx:2003-2014): Startup's default workspace with "Use current", and
 * Session behavior's three switches. Everything here edits the Save draft, never the store.
 */
@Composable
private fun GeneralPanel(live: TetherPreferences, state: SettingsDialogState, currentWorkspace: String, narrow: Boolean) {
    val draft = state.draftOr(live)
    Column {
        SettingsSection("Startup", AnnotatedString("Choose what Tether opens when you return."), narrow) {
            SettingsRow(
                narrow = narrow,
                text = { m ->
                    // The kept folder is server text (a path): drawn by the code-label rule.
                    val caption = draft.defaultWorkspace.takeIf { it.isNotEmpty() }?.let { codeLabel(it) }
                        ?: AnnotatedString(GeneralDraft.WORKSPACE_ROOT)
                    SettingsRowText(
                        "Default workspace",
                        caption,
                        m.testTag(SettingsPanelTags.DefaultWorkspace),
                        tip = "The folder pre-selected when you create a new session. Click 'Use current' to set it to your current workspace. Leave empty to use the server's workspace root.",
                    )
                },
                control = { m ->
                    TetherKey(
                        onClick = { state.edit { it.usingCurrent(currentWorkspace) } },
                        enabled = state.ready,
                        classes = KeyClasses.ButtonSecondary,
                        label = "Use current",
                        modifier = m.testTag(SettingsPanelTags.UseCurrent),
                    )
                },
            )
        }
        SettingsSection("Session behavior", AnnotatedString("How sessions are listed, ended, and narrated."), narrow, last = true) {
            GeneralToggle.entries.forEach { toggle ->
                SettingsToggleRow(
                    title = toggle.title,
                    caption = toggle.caption,
                    tip = toggle.tip,
                    checked = draft.isOn(toggle),
                    onToggle = { state.edit { it.toggled(toggle) } },
                    enabled = state.ready,
                    narrow = narrow,
                    modifier = Modifier.testTag(SettingsPanelTags.toggle(toggle)),
                )
            }
        }
    }
}

/**
 * Appearance (settings-dialog.tsx:2015-2064): NOT drafted. A pick applies and is stored at once,
 * the way the web's panel and every OS appearance pane behave, so these rows read the live
 * preference. The caption names the finish the mode resolves to.
 */
@Composable
private fun AppearancePanel(prefs: UiPrefs, live: TetherPreferences, narrow: Boolean) {
    val scope = rememberCoroutineScope()
    val dark = live.themeMode.isDark(isSystemInDarkTheme())
    val t = LocalTetherTokens.current
    val caption = buildAnnotatedString {
        append("Studio in light or dark, or matched to this device. Applied as you pick it, and remembered on this device. Now showing ")
        withStyle(SpanStyle(fontWeight = FontWeight(700), color = t.ink)) { append(if (dark) "Dark" else "Light") }
        append(".")
    }
    Column {
        SettingsSection("Appearance", caption, narrow) {
            ThemeChoices {
                ThemeMode.entries.forEach { mode ->
                    ThemeChoiceRow(
                        icon = when (mode) {
                            ThemeMode.Light -> TetherIcons.Sun
                            ThemeMode.Dark -> TetherIcons.Moon
                            ThemeMode.System -> TetherIcons.Monitor
                        },
                        label = mode.label,
                        hint = mode.hint,
                        selected = live.themeMode == mode,
                        onClick = { scope.launch { prefs.setThemeMode(mode) } },
                        narrow = narrow,
                        modifier = Modifier.testTag(SettingsPanelTags.themeMode(mode)),
                    )
                }
            }
        }
        SettingsSection(
            "Sign-in screen",
            AnnotatedString("How the console greets you before you sign in. Default is Studio's welcome sign-in on every new device; the choice is remembered on this one."),
            narrow,
            last = true,
        ) {
            ThemeChoices {
                AppearanceChoices.loginVariants.forEach { choice ->
                    ThemeChoiceRow(
                        icon = TetherIcons.Terminal,
                        label = choice.label,
                        hint = choice.hint,
                        selected = live.loginVariant == choice.value,
                        onClick = { scope.launch { prefs.setLoginVariant(choice.value) } },
                        narrow = narrow,
                        modifier = Modifier.testTag(SettingsPanelTags.loginVariant(choice.value.id)),
                    )
                }
            }
        }
    }
}

/**
 * Devices (settings-dialog.tsx:2065-2114): Notifications hosts this app's push controls (T12.1,
 * FCM, effect-on-use like the web's Web Push row); the web's Passkeys / Signed-in sessions
 * (`SignInSecuritySection`) and `PairedDevicesSection` follow, as slots for T10.4.
 */
@Composable
private fun DevicesPanel(prefs: UiPrefs, narrow: Boolean) {
    Column {
        SettingsSection(
            "Notifications",
            AnnotatedString("Private, generic alerts for approvals, agent questions, and completed turns."),
            narrow,
        ) {
            PushNotificationsRow(prefs)
        }
        // T10.4 slot: components/sign-in-security.tsx (Passkeys, Signed-in sessions).
        SettingsSection("Sign-in security", AnnotatedString("Passkeys and the browsers and devices signed in to this console."), narrow) {
            ComingSoonNote("Passkeys and signed-in sessions are coming to the app in a later update. Manage them from the web console for now.")
        }
        // T10.4 slot: components/paired-devices.tsx.
        SettingsSection("Paired devices", AnnotatedString("Native clients that hold their own access token for this server."), narrow, last = true) {
            ComingSoonNote("The paired-device list is coming to the app in a later update. Manage paired devices from the web console for now.")
        }
    }
}

/** Nodes (settings-dialog.tsx:2115-2117 `NodesSection`): the slot for T10.3. */
@Composable
private fun NodesPanel(narrow: Boolean) {
    SettingsSection("Nodes", AnnotatedString("Other Tether hosts this console can reach."), narrow, last = true) {
        ComingSoonNote("The node list, with each node's status and the add, probe and remove controls, is coming to the app in a later update.")
    }
}

/**
 * Engines (settings-dialog.tsx:2107-2249), in the web's order: the engines (Scan again, then one
 * card per engine; the slot for ta-dh1), Claude accounts (ta-9q2, read only), Custom providers
 * (`ProfilesEditor`; the slot for ta-q6p) and Host config (`shareHostConfig`; the slot for ta-dh1).
 */
@Composable
private fun EnginesPanel(narrow: Boolean, claudeAccounts: ClaudeAccountsBinding) {
    Column {
        // ta-dh1 slot: the Engines section and the engine cards.
        SettingsSection("Engines", AnnotatedString("Enable or disable headless engines. Toggling takes effect immediately — no restart."), narrow) {
            ComingSoonNote("Engine detection with Scan again, and each engine's switch, home, command and launch command, are coming to the app in a later update.")
        }
        ClaudeAccountsHost(claudeAccounts, narrow)
        // ta-q6p slot: ProfilesEditor.
        SettingsSection("Custom providers", AnnotatedString("Declarative profiles extending the built-in engines — different credentials, binaries, or model lists per profile."), narrow) {
            ComingSoonNote("The provider profiles editor is coming to the app in a later update.")
        }
        // ta-dh1 slot: Host config (shareHostConfig).
        SettingsSection("Host config", null, narrow, last = true) {
            ComingSoonNote("Share host config, which links an isolated engine home to your host CLI's skills, MCP servers, commands and agents, is coming to the app in a later update.")
        }
    }
}

/**
 * Metadata (settings-dialog.tsx 887c222 :2257-2320): the master switch, then the provider
 * selection: Mode, and the provider / model as a select of the configured fallback list (with a
 * "Custom" row for a hand-entered value) or, without a readable list, a free-text field.
 */
@Composable
private fun MetadataPanel(narrow: Boolean, binding: ServerSettingsBinding) {
    val view = binding.settings
    Column {
        if (view == null) {
            SettingsSection(MetadataRows.GENERATION, AnnotatedString(MetadataRows.GENERATION_CAPTION), narrow, last = true) { ServerSettingsLoading() }
            return@Column
        }
        SettingsSection(MetadataRows.GENERATION, AnnotatedString(MetadataRows.GENERATION_CAPTION), narrow) {
            ServerToggleRow(MetadataRows.enabled, view, binding, narrow)
        }
        SettingsSection(MetadataRows.SELECTION, AnnotatedString(MetadataRows.SELECTION_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("selection")), last = true) {
            ServerSelectRow(MetadataRows.mode, MetadataRows.modes, view, binding, narrow)
            val manual = view.choice(ServerSetting.MetadataGenerationMode) == "manual"
            val providers = MetadataRows.parseProviders(view.text(ServerSetting.MetadataGenerationProviders))
            val row = ServerRow(ServerSetting.MetadataGenerationProvider, MetadataRows.PROVIDER, "", "", MetadataRows.PROVIDER_PLACEHOLDER)
            if (providers != null) {
                ServerSelectRow(
                    row,
                    MetadataRows.providerOptions(view, providers),
                    view,
                    binding,
                    narrow,
                    description = MetadataRows.providerSelectDescription(manual),
                    tip = MetadataRows.PROVIDER_SELECT_TIP,
                )
            } else {
                ServerTextRow(row.copy(description = MetadataRows.providerTextDescription(manual), tip = MetadataRows.PROVIDER_TEXT_TIP), view, binding, narrow)
            }
        }
    }
}

/**
 * Advanced (settings-dialog.tsx 887c222 :2321-2449), in the web's order: Network, Authentication
 * (the two secrets, masked), Storage, GitHub connection, Session lifecycle, Session defaults, then
 * Claude CLI. Every row writes at once (`set-server-settings` with only its key); a value an
 * environment variable forces is locked. The restart banner above follows the server's
 * `restartRequired` in its reply. None of these rows sets what the server RUNS (the engine homes,
 * commands and launch command are on Engines, ta-dh1, behind a confirmation).
 *
 * Not here: the GitHub connection card (the `/api/github/connection` routes, MATRIX row `/api/github/...`, T8.4)
 * holds its place with a note; the active Codex / opencode session's provider controls
 * (settings-dialog.tsx:2380-2420, shown on the web only while such a session is open) stay in that
 * session's composer (T7.2).
 */
@Composable
private fun AdvancedPanel(narrow: Boolean, binding: ServerSettingsBinding) {
    val view = binding.settings
    Column {
        if (view == null) {
            SettingsSection("Server settings", null, narrow) { ServerSettingsLoading() }
        } else {
            SettingsSection(AdvancedRows.NETWORK, AnnotatedString(AdvancedRows.NETWORK_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("network"))) {
                ServerTextRow(AdvancedRows.host, view, binding, narrow)
                ServerNumberRow(AdvancedRows.port, view, binding, narrow)
            }
            SettingsSection(AdvancedRows.AUTH, AnnotatedString(AdvancedRows.AUTH_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("auth"))) {
                ServerSecretRow(AdvancedRows.password, view, binding, narrow)
                ServerSecretRow(AdvancedRows.proxyToken, view, binding, narrow)
            }
            SettingsSection(AdvancedRows.STORAGE, AnnotatedString(AdvancedRows.STORAGE_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("storage"))) {
                ServerTextRow(AdvancedRows.stateDir, view, binding, narrow)
                ServerTextRow(AdvancedRows.workspaceRoot, view, binding, narrow)
            }
            // T8.4 (MATRIX `/api/github/*`): GitHubConnectionSection is not ported in this slice.
            SettingsSection(AdvancedRows.GITHUB, AnnotatedString(AdvancedRows.GITHUB_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.GitHub)) {
                ComingSoonNote(AdvancedRows.GITHUB_LATER)
            }
            SettingsSection(AdvancedRows.LIFECYCLE, AnnotatedString(AdvancedRows.LIFECYCLE_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("lifecycle"))) {
                ServerToggleRow(AdvancedRows.claudePersistent, view, binding, narrow)
                ServerToggleRow(AdvancedRows.claudeTaskTelemetry, view, binding, narrow)
                ServerNumberRow(AdvancedRows.warmMaxSessions, view, binding, narrow)
                ServerNumberRow(AdvancedRows.maxConcurrentTurns, view, binding, narrow)
                ServerNumberRow(AdvancedRows.warmIdleEvictionMs, view, binding, narrow)
                ServerNumberRow(AdvancedRows.warmBgHardCapMs, view, binding, narrow)
                ServerNumberRow(AdvancedRows.warmSweepMs, view, binding, narrow)
                ServerNumberRow(AdvancedRows.shutdownDrainMs, view, binding, narrow)
                ServerSelectRow(AdvancedRows.messageInterruptMode, AdvancedRows.messageInterruptModes, view, binding, narrow)
                ServerSelectRow(AdvancedRows.claudeModelFallback, AdvancedRows.claudeModelFallbacks, view, binding, narrow)
                ServerToggleRow(AdvancedRows.archiveOnMerge, view, binding, narrow)
            }
            SettingsSection(AdvancedRows.DEFAULTS, AnnotatedString(AdvancedRows.DEFAULTS_CAPTION), narrow, modifier = Modifier.testTag(ServerSettingsTags.section("defaults"))) {
                ServerSelectRow(AdvancedRows.defaultPermissionMode, AdvancedRows.permissionModes, view, binding, narrow)
                ServerSelectRow(AdvancedRows.defaultSandboxPolicy, AdvancedRows.sandboxPolicies, view, binding, narrow)
                ServerToggleRow(AdvancedRows.defaultUseWorktree, view, binding, narrow)
                ServerRootsRow(AdvancedRows.allowedRoots, view, binding, narrow)
                ServerRootsRow(AdvancedRows.spawnExtraWritableRoots, view, binding, narrow)
                ServerSelectRow(AdvancedRows.preferSpawnAgent, AdvancedRows.preferSpawnAgents, view, binding, narrow)
            }
        }
        ClaudeCliSection(binding, narrow)
    }
}
