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
import com.tether.app.ui.prefs.launchPreferenceWrite
import com.tether.app.ui.text.codeLabel
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.ThemeMode

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
    providers: ProvidersBinding = ProvidersBinding.None,
    nodes: NodesBinding = NodesBinding.None,
    devices: DevicesBinding = DevicesBinding.None,
) {
    when (tab) {
        SettingsTab.General -> GeneralPanel(live, state, currentWorkspace, narrow)
        SettingsTab.Appearance -> AppearancePanel(prefs, live, narrow)
        // T10.4: keyed on the server, so another server's panel starts from nothing (no pending
        // confirmation, no rename half typed); the controller itself is one per server.
        SettingsTab.Devices -> key(devices.controller?.origin) { DevicesPanel(prefs, narrow, devices) }
        // T10.3: keyed on the server, so another server's form starts empty (the credential dropped,
        // nothing half-typed carried over); its requests are bound to their server.
        SettingsTab.Nodes -> key(nodes.origin) { NodesPanel(narrow, nodes) }
        SettingsTab.Engines -> EnginesPanel(narrow, claudeAccounts, serverSettings, providers)
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
                        onClick = { scope.launchPreferenceWrite { prefs.setThemeMode(mode) } },
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
                        onClick = { scope.launchPreferenceWrite { prefs.setLoginVariant(choice.value) } },
                        narrow = narrow,
                        modifier = Modifier.testTag(SettingsPanelTags.loginVariant(choice.value.id)),
                    )
                }
            }
        }
    }
}

/**
 * Devices (settings-dialog.tsx:2065-2103): Notifications hosts this app's push controls (T12.1,
 * FCM, effect-on-use like the web's Web Push row); then the web's `SignInSecuritySection`
 * (Passkeys, Signed-in sessions) and `PairedDevicesSection` (T10.4, DevicesSection.kt).
 */
@Composable
private fun DevicesPanel(prefs: UiPrefs, narrow: Boolean, devices: DevicesBinding) {
    Column {
        SettingsSection(
            "Notifications",
            AnnotatedString("Private, generic alerts for approvals, agent questions, and completed turns."),
            narrow,
        ) {
            PushNotificationsRow(prefs)
        }
        DevicesSecuritySections(devices, narrow)
    }
}

/** Nodes (settings-dialog.tsx 887c222 :2104 `NodesSection`; T10.3, NodesSection.kt). */
@Composable
private fun NodesPanel(narrow: Boolean, nodes: NodesBinding) {
    NodesSection(nodes, narrow)
}

/**
 * Engines (settings-dialog.tsx:2107-2249), in the web's order: the engines (Scan again, then one
 * card per engine; ta-dh1, EnginesSection.kt), Claude accounts (ta-9q2, read only), Custom
 * providers (`ProfilesEditor`, :2237; ta-q6p, ProfilesSection.kt) and Host config
 * (`shareHostConfig`; ta-dh1). The engine parts and the profiles are keyed on the server, so
 * another server's cards start from nothing (no half-typed field, no revealed env value, no
 * pending confirmation; an edit is bound to its own server).
 */
@Composable
private fun EnginesPanel(narrow: Boolean, claudeAccounts: ClaudeAccountsBinding, serverSettings: ServerSettingsBinding, providers: ProvidersBinding) {
    val loaded = serverSettings.settings != null && serverSettings.origin != null
    Column {
        key(serverSettings.origin) { EngineCards(serverSettings, narrow) }
        ClaudeAccountsHost(claudeAccounts, narrow)
        key(providers.origin) { ProfilesSection(providers, narrow, last = !loaded) }
        // Like the web, Host config is drawn once the server's settings are in.
        key(serverSettings.origin) { HostConfigSection(serverSettings, narrow) }
    }
}

/**
 * Metadata (settings-dialog.tsx 887c222 :2257-2320): the master switch, then the provider
 * selection: Mode, and the provider / model as a select of the configured fallback list (with a
 * "Custom" row for a hand-entered value) or, without a readable list, a free-text field.
 */
@Composable
private fun MetadataPanel(narrow: Boolean, binding: ServerSettingsBinding) {
    // r2: no server, nothing drawn (a frame kept past a sign-out must never show).
    val view = binding.settings?.takeIf { binding.origin != null }
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
 * `restartRequired` in its reply. The Claude CLI picker writes at once like the web's (ta-coik.5:
 * no app-only confirmation). The engine homes, commands and launch command are on Engines (ta-dh1,
 * EnginesSection.kt), written as the web's blur writes them.
 *
 * Not here: the GitHub connection card (the `/api/github/connection` routes, MATRIX row `/api/github/...`, T8.4)
 * holds its place with a note; the active Codex / opencode session's provider controls
 * (settings-dialog.tsx:2380-2420, shown on the web only while such a session is open) stay in that
 * session's composer (T7.2).
 */
@Composable
private fun AdvancedPanel(narrow: Boolean, binding: ServerSettingsBinding) {
    // r2: no server, nothing drawn (a frame kept past a sign-out must never show).
    val view = binding.settings?.takeIf { binding.origin != null }
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
