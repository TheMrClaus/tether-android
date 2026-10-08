package com.tether.app.ui.settings

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.ClaudeAccount
import com.tether.app.client.ClaudeAccountRefusal
import com.tether.app.client.ClaudeAccountStatus
import com.tether.app.client.ClaudeAccountsResult
import com.tether.app.client.ClaudeAccountsSource
import com.tether.app.client.ClaudeAccountsSync
import com.tether.app.client.ServerSetting
import com.tether.app.protocol.ClientMessage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.PreferenceKeys
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dialog's seeded states at the web's device classes (phone 412×915 @2.625: full screen;
 * tablet 1280×800 @1: the centred dialog), both Studio skins, plus 1.3× font. Fresh preferences
 * but the skin's mode (the web reference's own state: ended sessions and confirm on, thinking off,
 * no default folder).
 * `settings-general` / `-appearance` / `-devices` / `-engines` are those tabs; `settings-restart` is
 * General under the restart banner. ta-9q2: the Engines shots are scrolled to Claude accounts:
 * `-engines` the seeded list (two accounts checked: one signed in, one refused), `-engines-sync`
 * its sync rows, `-engines-loading` / `-blocked` / `-error` the other states. ta-7rh: `-engines-owner`
 * a pre-#236 server's owner refusal, `-engines-login` a login waiting for its code, `-engines-adding`
 * the nickname row with a refusal, `-engines-rename` Rename open (and a card's failure line).
 */
enum class SettingsShot(
    val id: String,
    val tab: SettingsTab,
    val restart: Boolean = false,
    val accounts: AccountsShot? = null,
    val server: ServerShot? = null,
    val profiles: ProfilesShot? = null,
    val nodes: NodesShot? = null,
    val devices: DevicesShot? = null,
    val github: GitHubShot? = null,
    /** T12.2: the push registration's status behind the Devices tab's push row. */
    val push: com.tether.app.push.PushRegistrationStatus = com.tether.app.push.PushRegistrationStatus.Registered,
) {
    General("settings-general", SettingsTab.General),
    Appearance("settings-appearance", SettingsTab.Appearance),
    Devices("settings-devices", SettingsTab.Devices, devices = DevicesShot.Top),
    DevicesSessions("settings-devices-sessions", SettingsTab.Devices, devices = DevicesShot.Sessions),
    DevicesPaired("settings-devices-paired", SettingsTab.Devices, devices = DevicesShot.Paired),
    DevicesSelf("settings-devices-self", SettingsTab.Devices, devices = DevicesShot.Self),
    DevicesTokenPassword("settings-devices-token-password", SettingsTab.Devices, devices = DevicesShot.TokenPassword),
    DevicesCode("settings-devices-code", SettingsTab.Devices, devices = DevicesShot.Code),
    DevicesCodeExpired("settings-devices-code-expired", SettingsTab.Devices, devices = DevicesShot.CodeExpired),
    DevicesOwner("settings-devices-owner", SettingsTab.Devices, devices = DevicesShot.Owner),
    DevicesChecking("settings-devices-checking", SettingsTab.Devices, devices = DevicesShot.Checking),
    DevicesError("settings-devices-error", SettingsTab.Devices, devices = DevicesShot.Error),
    DevicesPasskeyEmpty("settings-devices-passkey-empty", SettingsTab.Devices, devices = DevicesShot.PasskeyEmpty),
    DevicesPasskeyAdding("settings-devices-passkey-adding", SettingsTab.Devices, devices = DevicesShot.PasskeyAdding),
    DevicesPasskeyDuplicate("settings-devices-passkey-duplicate", SettingsTab.Devices, devices = DevicesShot.PasskeyDuplicate),
    DevicesPasskeyAdded("settings-devices-passkey-added", SettingsTab.Devices, devices = DevicesShot.PasskeyAdded),
    /** T12.2: the push row stale (the server names another Firebase project): Re-enable. */
    DevicesPushStale("settings-devices-push-stale", SettingsTab.Devices, devices = DevicesShot.Top, push = com.tether.app.push.PushRegistrationStatus.ProjectChanged),
    Engines("settings-engines", SettingsTab.Engines, accounts = AccountsShot.Loaded),
    EnginesSync("settings-engines-sync", SettingsTab.Engines, accounts = AccountsShot.Sync),
    EnginesLoading("settings-engines-loading", SettingsTab.Engines, accounts = AccountsShot.Loading),
    EnginesBlocked("settings-engines-blocked", SettingsTab.Engines, accounts = AccountsShot.Blocked),
    EnginesError("settings-engines-error", SettingsTab.Engines, accounts = AccountsShot.Error),
    EnginesOwner("settings-engines-owner", SettingsTab.Engines, accounts = AccountsShot.Owner),
    EnginesLogin("settings-engines-login", SettingsTab.Engines, accounts = AccountsShot.Login),
    EnginesLoginElsewhere("settings-engines-login-elsewhere", SettingsTab.Engines, accounts = AccountsShot.LoginElsewhere),
    EnginesAdding("settings-engines-adding", SettingsTab.Engines, accounts = AccountsShot.Adding),
    EnginesRename("settings-engines-rename", SettingsTab.Engines, accounts = AccountsShot.Rename),
    Restart("settings-restart", SettingsTab.General, restart = true),
    Advanced("settings-advanced", SettingsTab.Advanced, server = ServerShot.Advanced, github = GitHubShot.Fresh),
    AdvancedRevealed("settings-advanced-revealed", SettingsTab.Advanced, server = ServerShot.Revealed, github = GitHubShot.Fresh),
    AdvancedLocked("settings-advanced-locked", SettingsTab.Advanced, server = ServerShot.Locked, github = GitHubShot.Fresh),
    AdvancedLifecycle("settings-advanced-lifecycle", SettingsTab.Advanced, server = ServerShot.Lifecycle, github = GitHubShot.Fresh),
    AdvancedDefaults("settings-advanced-defaults", SettingsTab.Advanced, server = ServerShot.Defaults, github = GitHubShot.Fresh),
    AdvancedCli("settings-advanced-cli", SettingsTab.Advanced, server = ServerShot.Cli, github = GitHubShot.Fresh),
    AdvancedGitHub("settings-advanced-github", SettingsTab.Advanced, server = ServerShot.GitHub, github = GitHubShot.Fresh),
    AdvancedGitHubDevice("settings-advanced-github-device", SettingsTab.Advanced, server = ServerShot.GitHub, github = GitHubShot.Device),
    AdvancedGitHubConnected("settings-advanced-github-connected", SettingsTab.Advanced, server = ServerShot.GitHub, github = GitHubShot.Connected),
    AdvancedGitHubErrors("settings-advanced-github-errors", SettingsTab.Advanced, server = ServerShot.GitHubToken, github = GitHubShot.Errors),
    Metadata("settings-metadata", SettingsTab.Metadata, server = ServerShot.Metadata),
    MetadataText("settings-metadata-text", SettingsTab.Metadata, server = ServerShot.MetadataText),
    EnginesCards("settings-engines-cards", SettingsTab.Engines, server = ServerShot.Engines),
    EnginesMissing("settings-engines-missing", SettingsTab.Engines, server = ServerShot.EnginesMissing),
    EnginesLocked("settings-engines-locked", SettingsTab.Engines, server = ServerShot.EnginesLocked),
    Profiles("settings-profiles", SettingsTab.Engines, profiles = ProfilesShot.Loaded),
    ProfilesEnv("settings-profiles-env", SettingsTab.Engines, profiles = ProfilesShot.Env),
    Nodes("settings-nodes", SettingsTab.Nodes, nodes = NodesShot.List),
    NodesEmpty("settings-nodes-empty", SettingsTab.Nodes, nodes = NodesShot.Empty),
    NodesAdding("settings-nodes-adding", SettingsTab.Nodes, nodes = NodesShot.Adding),
    NodesResult("settings-nodes-result", SettingsTab.Nodes, nodes = NodesShot.Result),
    NodesError("settings-nodes-error", SettingsTab.Nodes, nodes = NodesShot.Error),
}

/**
 * T10.3: the Nodes seeds, timing-free like the others: the list and the last answer are built HERE
 * and handed in (the answer as the actions' initial notice), so the first frame is the drawn tab;
 * the writer behind them fails the shot on any request. `settings-nodes` the list (every status,
 * the skew warning), `-empty` no nodes, `-adding` the form filled (ta-coik.5: the credential shown
 * as the web's textarea shows it, an obviously FAKE bundle), `-result` a probe's answer, `-error` the
 * refusal a phone sign-in gets before tether #236 is deployed. The form is filled by synchronous
 * semantics actions on the hand-driven clock, then the focus cleared (no cursor in the shot).
 */
enum class NodesShot(
    val scrollTo: String?,
    val list: List<com.tether.app.protocol.NodeSummary> = NodeFixtures.LIST,
    val fill: Boolean = false,
    val notice: NodeNotice? = null,
) {
    List(NodeTags.row("node_ws")),
    Empty(NodeTags.Add, list = emptyList()),
    Adding(null, fill = true),
    Result(NodeTags.Add, notice = NodeNotice(NodeFixtures.ORIGIN, NodeAction.Probe, "node_ws", ok = true, text = "Reachable.", heldByServer = false, serial = 1)),
    Error(NodeTags.Add, notice = NodeNotice(NodeFixtures.ORIGIN, NodeAction.Add, null, ok = false, text = NodeFixtures.REFUSAL, heldByServer = false, serial = 1)),
    ;

    fun binding(actions: NodesActions) = NodesBinding(list, NodeFixtures.ORIGIN, actions, NodeFixtures.CONSOLE, now = { NodeFixtures.NOW })
}

/**
 * T10.4: the Devices seeds, timing-free like the others: the controller is handed a state built HERE
 * (so the first frame is the drawn tab and nothing is read), over a source that fails the shot on
 * any call, the clock fixed. `settings-devices` the top (Notifications, Passkeys), `-sessions` the
 * signed-in sessions (the app's passkey session marked), `-paired` the paired devices with the pair
 * hint, `-self` a device-token sign-in whose only device is this phone, `-token-password` the
 * password switch from a device-token sign-in (ta-coik.5: usable, as on the web),
 * `-code` a fresh code, shown at once as on the web (an obviously FAKE code), `-code-expired` the expired card (the code kept with Copy, as on the web),
 * `-owner` the owner-grade refusal before tether #236 is deployed, `-checking` the opening reads in
 * flight, `-error` a refusal in each area. T10.5 (the panel's passkey prompt available, as on a
 * device): `-passkey-empty` none yet, the add row ready (the web reference's own state),
 * `-passkey-adding` the ceremony in flight ("Adding…", every key held), `-passkey-duplicate`
 * the web's words for an authenticator that already has one, `-passkey-added` the notice and the new
 * row after the re-read.
 */
enum class DevicesShot(val scrollTo: String?) {
    Top(null),
    Sessions(DevicesTags.Sessions),
    Paired(DevicesTags.Paired),
    Self(DevicesTags.Paired),
    TokenPassword(DevicesTags.PasswordToggle),
    Code(DevicesTags.Paired),
    CodeExpired(DevicesTags.Paired),
    Owner(null),
    Checking(null),
    Error(DevicesTags.Paired),
    PasskeyEmpty(DevicesTags.Passkeys),
    PasskeyAdding(DevicesTags.Passkeys),
    PasskeyDuplicate(DevicesTags.Passkeys),
    PasskeyAdded(DevicesTags.Passkeys),
    ;

    fun seed(): DevicesSeed = when (this) {
        Top, Sessions, Paired -> DevicesFixtures.seed()
        Self -> DevicesFixtures.seed(com.tether.app.client.AppSignIn.DeviceToken, devices = listOf(DevicesFixtures.PHONE), sessions = listOf(DevicesFixtures.BROWSER))
        TokenPassword -> DevicesFixtures.seed(com.tether.app.client.AppSignIn.DeviceToken, sessions = listOf(DevicesFixtures.BROWSER))
        Code -> DevicesFixtures.seed(code = DevicesFixtures.code(DevicesFixtures.FAKE_CODE))
        CodeExpired -> DevicesFixtures.seed(code = DevicesFixtures.code(DevicesFixtures.FAKE_CODE, expiresAt = DevicesFixtures.NOW - 1_000))
        Owner -> DevicesSeed(ownerNeeded = true, signIn = com.tether.app.client.AppSignIn.DeviceToken)
        Checking -> DevicesSeed()
        Error -> DevicesFixtures.seed().copy(
            devicesLine = DevicesLine("No such device.", error = true),
            securityLine = DevicesLine("Sign in with a passkey first, then turn password sign-in off — this proves the passkey works before it becomes the only way in.", error = true),
        )
        PasskeyEmpty -> DevicesFixtures.seed().copy(passkeys = DevicesFixtures.NO_PASSKEYS)
        PasskeyAdding -> DevicesFixtures.seed().copy(securityBusy = DevicesAction.AddPasskey)
        PasskeyDuplicate -> DevicesFixtures.seed().copy(passkeys = DevicesFixtures.NO_PASSKEYS, securityLine = DevicesLine(DevicesCopy.PASSKEY_DUPLICATE, error = true))
        PasskeyAdded -> DevicesFixtures.seed().copy(
            passkeys = DevicesFixtures.PASSKEYS.copy(passkeys = listOf(PasskeyShapes.NEW_KEY) + DevicesFixtures.PASSKEYS.passkeys),
            securityLine = DevicesLine(DevicesCopy.PASSKEY_ADDED, error = false),
        )
    }
}

/**
 * ta-q6p: the Custom providers seeds, timing-free like the others: the list is built HERE and handed
 * in, so the first frame is the drawn editor and nothing is fetched or written (the writer behind it
 * fails the shot if asked). `settings-profiles` the section's top (the caption and the Gemini CLI
 * acp card), `-env` the env editor, its value shown as the web's plain field shows it (an obviously
 * FAKE key; ta-coik.5).
 */
enum class ProfilesShot(val scrollTo: String) {
    Loaded(ProfileTags.Section),
    Env(ProfileTags.row("gemini", ProfileTags.ENV)),
    ;

    fun binding() = ProvidersBinding(ProfileFixtures.list(), ProfileFixtures.ORIGIN, NeverWritesProviders)
}

/** The providers writer behind a seeded shot: a write is a timing dependency (and a bug), so it fails the shot. */
private object NeverWritesProviders : ProvidersWriter {
    override fun setProviders(write: com.tether.app.client.ProvidersWrite, origin: String): com.tether.app.client.ProvidersRefusal? = error("a seeded shot must not write")
}

/**
 * ta-t7l: the Advanced and Metadata seeds, timing-free like the accounts ones: the frames are built
 * HERE and handed in, so the first frame is the drawn tab and nothing is fetched or written (the
 * writer behind them fails the shot if asked). [scrollTo]: the section brought to the top;
 * [reveal]: the one tap of a shot (the Reveal key, a synchronous state change on the hand-driven
 * clock), on an obviously fake password.
 * `-advanced` the top (Network, the masked secrets, Storage), `-revealed` the password shown,
 * `-locked` env-forced rows (and the CLI forced), `-lifecycle` / `-defaults` / `-cli` the later
 * sections, `-metadata` the provider select (manual), `-metadata-text` the free-text fallback.
 * ta-dh1: `settings-engines-cards` the Engines tab's top (Scan again, Claude Code and Codex on),
 * `-missing` OpenCode (not found, no home: its switch blocked, the detected home offered),
 * `-locked` the engines and Codex's home and command set by the environment.
 */
enum class ServerShot(val scrollTo: String? = null, val reveal: Boolean = false) {
    Advanced,
    Revealed(ServerSettingsTags.section("auth"), reveal = true),
    Locked,
    Lifecycle(ServerSettingsTags.section("lifecycle")),
    Defaults(ServerSettingsTags.section("defaults")),
    Cli(ServerSettingsTags.section("cli")),
    Metadata,
    MetadataText,
    Engines,
    EnginesMissing(EngineTags.card(com.tether.app.client.EngineCard.Opencode)),
    EnginesLocked(EngineTags.card(com.tether.app.client.EngineCard.Codex)),
    /** ta-coik.21: Advanced scrolled to the GitHub connection. */
    GitHub(ServerSettingsTags.GitHub),
    /** ta-coik.21: Advanced scrolled to the GitHub token rows (a refusal under the field in frame). */
    GitHubToken(GitHubTags.Pat),
    ;

    fun binding(): ServerSettingsBinding = when (this) {
        Locked -> ServerFixtures.binding(
            view = ServerFixtures.view(envForced = mapOf("host" to true, "port" to true, "password" to true, "stateDir" to true, "workspaceRoot" to true)),
            advanced = ServerFixtures.ADVANCED_FORCED,
            writer = NeverWrites,
        )
        EnginesLocked -> ServerFixtures.binding(
            view = ServerFixtures.view(envForced = mapOf("stateDir" to true, "headlessModes" to true, "codexHome" to true, "codexCommand" to true)),
            writer = NeverWrites,
        )
        MetadataText -> ServerFixtures.binding(
            view = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("metadataGenerationProviders" to null, "metadataGenerationMode" to "automatic", "metadataGenerationProvider" to null))),
            writer = NeverWrites,
        )
        else -> ServerFixtures.binding(writer = NeverWrites)
    }
}

/**
 * ta-coik.21: the GitHub connection seeds, timing-free like the others: the controller is built from a
 * state made HERE (so nothing is read on creation), over a source that fails the shot on any call.
 * `-advanced-github` gh installed with no login (the web reference's fresh state: Re-check, Start device
 * flow, the empty token field); `-device` the device flow with its one-time code (GitHub's own example
 * code), Waiting… held, open and Cancel; `-connected` a managed token (Remove Tether token, the form
 * gone); `-errors` (scrolled to the token rows) gh not installed, a failed device flow's line, a typed (masked, obviously FAKE) token
 * and the server's refusal of it. Every Advanced shot draws [Fresh] in its place.
 */
enum class GitHubShot {
    Fresh,
    Device,
    Connected,
    Errors,
    ;

    fun seed(): GitHubSeed = when (this) {
        Fresh -> GitHubSeed(status = GitHubFixtures.NOT_LOGGED_IN)
        Device -> GitHubSeed(status = GitHubFixtures.NOT_LOGGED_IN, deviceFlow = true, poll = GitHubFixtures.pending("WDJB-MJHT", "https://github.com/login/device"))
        Connected -> GitHubSeed(status = GitHubFixtures.MANAGED)
        Errors -> GitHubSeed(
            status = GitHubFixtures.NOT_INSTALLED,
            poll = com.tether.app.client.GitHubDevicePoll(true, com.tether.app.client.GitHubDeviceStatus.Error, null, null, "gh auth login exited with code 1."),
            tokenInput = GitHubFixtures.FAKE_TOKEN,
            tokenError = "That token could not be verified. Check the value and its scopes.",
        )
    }
}

/** The GitHub source behind a seeded shot: any call is a timing dependency, so it fails the shot. */
private object NeverCalledGitHub : com.tether.app.client.GitHubConnectionSource {
    override suspend fun status(origin: String): com.tether.app.client.SecurityResult<com.tether.app.client.GitHubConnectionStatus> = error("a seeded shot must not read the status")
    override suspend fun startLogin(origin: String): com.tether.app.client.SecurityResult<Unit> = error("a seeded shot must not start a login")
    override suspend fun pollLogin(origin: String): com.tether.app.client.SecurityResult<com.tether.app.client.GitHubDevicePoll> = error("a seeded shot must not poll")
    override suspend fun cancelLogin(origin: String): com.tether.app.client.SecurityResult<Unit> = error("a seeded shot must not cancel")
    override suspend fun saveToken(origin: String, token: com.tether.app.client.GitHubToken): com.tether.app.client.SecurityResult<com.tether.app.client.GitHubTokenSaved> = error("a seeded shot must not send a token")
    override suspend fun logout(origin: String): com.tether.app.client.SecurityResult<Unit> = error("a seeded shot must not log out")
}

/** The writer behind a seeded shot: a write is a timing dependency (and a bug), so it fails the shot. */
private object NeverWrites : ServerSettingsWriter {
    override fun patch(patch: JsonObject, origin: String): Boolean = error("a seeded shot must not write")
    override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean = error("a seeded shot must not write")
    override fun detectEngines(origin: String): Boolean = error("a seeded shot must not scan")
}

/**
 * The Claude accounts seeds of the Engines shots, and where each is scrolled to. Timing-free by
 * construction, like the preferences: the section's state is built HERE and handed in as its
 * initial state, so the first frame is the seeded section and the source is never asked (no fetch,
 * no tap, no round trip). The source behind it would fail the shot if it were.
 */
enum class AccountsShot(val scrollTo: String) {
    Loaded(ClaudeAccountsTags.Section),
    Sync(ClaudeAccountsTags.Sync),
    Loading(ClaudeAccountsTags.Section),
    Blocked(ClaudeAccountsTags.Section),
    Error(ClaudeAccountsTags.Section),
    /** ta-7rh: a server without #236 refused a change: the note and Try again, every change at rest. */
    Owner(ClaudeAccountsTags.Section),
    /** ta-7rh: a login waiting for its code: the link's host, Open, the empty code field and Submit. */
    Login(ClaudeAccountsTags.loginPanel("claude-work")),
    /** ta-7rh r2: a login link to a long host that is not Anthropic's: its end shown, the quiet warning. */
    LoginElsewhere(ClaudeAccountsTags.loginPanel("claude-work")),
    /** ta-7rh: the nickname row open with a name typed, and the server's refusal under it. */
    Adding(ClaudeAccountsTags.AddField),
    /** ta-7rh: Rename open on "work" (filled with the nickname), its Remove armed ("Confirm remove"), and a failure line under another card. */
    Rename(ClaudeAccountsTags.card("claude-work")),
    ;

    /** The changes' state of the shot (null: none; the reads' seed alone). */
    fun writeSeed(): AccountsWriteSeed? = when (this) {
        Owner -> AccountsWriteSeed(ownerNeeded = true)
        Login -> AccountsWriteSeed(
            logins = mapOf("claude-work" to LoginPanel(com.tether.app.client.ClaudeLoginStatus.AwaitingCode, com.tether.app.client.ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?code=true&client_id=FAKE&state=FAKE"))),
        )
        LoginElsewhere -> AccountsWriteSeed(
            logins = mapOf("claude-work" to LoginPanel(com.tether.app.client.ClaudeLoginStatus.AwaitingCode, com.tether.app.client.ClaudeLoginLink.parse("https://claude.ai.oauth.sign-in-for-your-account-to-continue-securely.evil.example/oauth/authorize"))),
        )
        Adding -> AccountsWriteSeed(adding = true, addText = "home", line = AccountsLine(ClaudeAccountsCopy.ADD_FAILED, error = true))
        Rename -> AccountsWriteSeed(
            renaming = "claude-work",
            renameText = "work",
            // r2: the web's armed Remove ("Confirm remove", tap again within 4 s).
            armedRemove = "claude-work",
            deleteCredentials = mapOf("claude-fresh" to true),
            lines = mapOf("claude-fresh" to AccountsLine(com.tether.app.client.ClaudeAccountActionCopy.NOT_A_CLAUDE_ACCOUNT, error = true)),
        )
        else -> null
    }

    fun seed(): ClaudeAccountsState {
        val origin = AccountsFixtures.ORIGIN
        val loaded = ClaudeAccountsState(
            origin = origin,
            accounts = AccountsFixtures.LIST,
            statuses = mapOf(
                "claude-work" to AccountStatusState.Known(AccountsFixtures.LOGGED_IN),
                "claude-fresh" to AccountStatusState.Failed(AccountsFault.Refused(ClaudeAccountRefusal.NoSuchAccount)),
            ),
            sync = AccountsFixtures.SYNC,
        )
        return when (this) {
            Loaded, Sync, Owner, Login, LoginElsewhere, Adding, Rename -> loaded
            Loading -> ClaudeAccountsState(origin = origin)
            Blocked -> ClaudeAccountsState(origin = origin, listFault = AccountsFault.Blocked(302))
            Error -> ClaudeAccountsState(origin = origin, listFault = AccountsFault.Unavailable(500))
        }
    }

    fun binding() = ClaudeAccountsBinding(NeverAsked, AccountsFixtures.ORIGIN, AccountsFixtures.TIME, initial = seed(), actions = NeverChanged, writeSeed = writeSeed())
}

/** The changes behind a seeded shot: any call is a timing dependency (and a write), so it fails the shot. */
private object NeverChanged : com.tether.app.client.ClaudeAccountActions by com.tether.app.client.ClaudeAccountActions.Unavailable {
    override suspend fun add(origin: String, nickname: String): com.tether.app.client.SecurityResult<Unit> = error("a seeded shot must not change anything")
    override suspend fun pollLogin(origin: String, accountId: String): com.tether.app.client.SecurityResult<com.tether.app.client.ClaudeLoginState> = error("a seeded shot must not poll")
    override suspend fun runSync(origin: String): com.tether.app.client.SecurityResult<com.tether.app.client.ClaudeSyncSaved> = error("a seeded shot must not sync")
}

/** The source behind a seeded shot: any call is a timing dependency, so it fails the shot. */
private object NeverAsked : ClaudeAccountsSource {
    override suspend fun list(): ClaudeAccountsResult<List<ClaudeAccount>> = error("a seeded shot must not read the list")
    override suspend fun sync(): ClaudeAccountsResult<ClaudeAccountsSync> = error("a seeded shot must not read the sync state")
    override suspend fun status(accountId: String): ClaudeAccountsResult<ClaudeAccountStatus> = error("a seeded shot must not read a status")
    override suspend fun alias(accountId: String): ClaudeAccountsResult<com.tether.app.client.ClaudeAccountAlias> = error("a seeded shot must not read an alias")
}

fun ComposeContentTestRule.snapSettings(
    store: PrefsStore,
    shot: SettingsShot,
    skin: TetherSkin,
    size: String,
    name: String = shot.id,
    /** The shell's own switch: the existing shots say it by their size name; the band shots (ta-smj8) by the width. */
    layout: TetherLayoutClass = if (size == "tablet") TetherLayoutClass.Expanded else TetherLayoutClass.Phone,
) {
    // The stored mode is the skin shown (the web reference's seeded state), so Appearance agrees.
    store.seed(PreferenceKeys.THEME_MODE to skin.mode.id)
    // Timing-free by construction (T10.1 r4): the stored preferences are read HERE, and the frame
    // gets them as its live value and its seeded draft, so its first frame is already the loaded
    // dialog (the not-loaded state never renders). Nothing in it animates from a start value then:
    // keys, switches and choice rows compose at their resting look. The clock is driven by hand.
    val stored = runBlocking { store.prefs.preferences.first() }
    val state = SettingsDialogState(shot.tab, GeneralDraft.of(stored))
    mainClock.autoAdvance = false
    // T12.2: notifications allowed, so the push row shows the registration (not the permission).
    grantNotificationPermission()
    var focus: androidx.compose.ui.focus.FocusManager? = null
    setContent {
        focus = androidx.compose.ui.platform.LocalFocusManager.current
        val nodeActions = shot.nodes?.let { rememberNodesActions(NeverWritesNodes, it.notice) }
        val devicesController = shot.devices?.let { rememberDevicesController(NeverCalledSecurity, DevicesFixtures.ORIGIN, it.seed(), authenticator = NeverPromptsPasskeys) }
        val githubScope = androidx.compose.runtime.rememberCoroutineScope()
        val github = shot.github?.let { g -> androidx.compose.runtime.remember { GitHubConnectionController(NeverCalledGitHub, ServerFixtures.ORIGIN, githubScope, seed = g.seed()) } }
        SettingsUnderTest(
            store.prefs,
            state,
            mode = skin.mode,
            layout = layout,
            restartRequired = shot.restart,
            initialPreferences = stored,
            claudeAccounts = shot.accounts?.binding() ?: ClaudeAccountsBinding.None,
            serverSettings = shot.server?.binding() ?: ServerSettingsBinding.None,
            providers = shot.profiles?.binding() ?: ProvidersBinding.None,
            nodes = if (shot.nodes != null && nodeActions != null) shot.nodes.binding(nodeActions) else NodesBinding.None,
            devices = devicesController?.let { DevicesBinding(it, now = { DevicesFixtures.NOW }) } ?: DevicesBinding.None,
            github = github?.let { GitHubBinding(it) } ?: GitHubBinding.None,
            push = androidx.compose.runtime.remember { FakePushRegistration(shot.push) },
        )
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot.accounts != null) {
        // ta-7rh: a field that took focus on opening (Add, Rename) is drawn at rest: no cursor in the shot.
        runOnIdle { focus?.clearFocus(force = true) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot.nodes?.fill == true) {
        onNodeWithTag(NodeTags.Label, useUnmergedTree = true).performTextReplacement("Workstation")
        onNodeWithTag(NodeTags.BaseUrl, useUnmergedTree = true).performTextReplacement("http://10.0.0.2:4173")
        onNodeWithTag(NodeTags.Credential, useUnmergedTree = true).performTextReplacement(NodeFixtures.FAKE_CREDENTIAL)
        runOnIdle { focus?.clearFocus(force = true) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot.server?.reveal == true) {
        onNodeWithTag(ServerSettingsTags.reveal(ServerSetting.Password), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    (shot.accounts?.scrollTo ?: shot.server?.scrollTo ?: shot.profiles?.scrollTo ?: shot.nodes?.scrollTo ?: shot.devices?.scrollTo)?.let { scrollTo ->
        // Bring the section (or its sync rows) to the top of the dialog's body: the offset is
        // measured on the laid-out frame (positionInRoot: boundsInRoot is clipped to what the body
        // shows), and the scroll runs out on the hand-driven clock.
        val body = onNodeWithTag(SettingsDialogTags.Body)
        val target = onNodeWithTag(scrollTo, useUnmergedTree = true)
        repeat(2) {
            val delta = target.fetchSemanticsNode().positionInRoot.y - body.fetchSemanticsNode().positionInRoot.y
            if (kotlin.math.abs(delta) > 0.5f) body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
        }
    }
    onRoot().captureRoboImage(
        "src/test/screenshots/$name/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

abstract class SettingsShotBase {
    private val tmp = TemporaryFolder()
    protected val store = PrefsStore(tmp)
    protected val rule = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(rule)
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsPhoneScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettingsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SettingsTabletScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "tablet")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettingsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale does not break the dialog (General, Appearance, the Claude accounts list and a login (ta-7rh), Advanced and its GitHub device flow (ta-coik.21), Metadata, the engine cards, the custom providers, the Nodes list and form, and the Devices top, paired devices and pairing code; Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SettingsFontScaleScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone", name = "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SettingsShot.General, SettingsShot.Appearance, SettingsShot.Engines, SettingsShot.EnginesLogin, SettingsShot.Advanced, SettingsShot.AdvancedGitHubDevice, SettingsShot.Metadata, SettingsShot.EnginesCards, SettingsShot.Profiles, SettingsShot.Nodes, SettingsShot.NodesAdding, SettingsShot.Devices, SettingsShot.DevicesPaired, SettingsShot.DevicesCode).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/**
 * ta-smj8 (W22): the rows at the web's two row breakpoints, 35rem (560) and 640px, either side of each: 560 and 561,
 * 640 and 641 dp, 900 tall, mdpi, Studio only (layout does not vary by skin). The shell's layout class mirrors
 * `settingsLayout()` (Phone at 640 and under); the 35rem row switch is left to its default, which reads the same width.
 */
abstract class SettingsBandShotBase(private val shot: SettingsShot, private val width: Int) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(
        store, shot, TetherSkin.Studio, "w$width",
        name = shot.id, layout = if (width <= 640) TetherLayoutClass.Phone else TetherLayoutClass.Expanded,
    )
}

object SettingsBandShots {
    val shots = listOf(
        SettingsShot.General, SettingsShot.Devices, SettingsShot.DevicesPaired, SettingsShot.Nodes,
        SettingsShot.AdvancedCli, SettingsShot.Metadata, SettingsShot.EnginesCards,
    )

    @JvmStatic fun params(): List<Array<Any>> = shots.map { arrayOf<Any>(it) }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w560dp-h900dp-mdpi")
class SettingsBand560ScreenshotTest(shot: SettingsShot) : SettingsBandShotBase(shot, 560) {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = SettingsBandShots.params() }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w561dp-h900dp-mdpi")
class SettingsBand561ScreenshotTest(shot: SettingsShot) : SettingsBandShotBase(shot, 561) {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = SettingsBandShots.params() }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w640dp-h900dp-mdpi")
class SettingsBand640ScreenshotTest(shot: SettingsShot) : SettingsBandShotBase(shot, 640) {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = SettingsBandShots.params() }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w641dp-h900dp-mdpi")
class SettingsBand641ScreenshotTest(shot: SettingsShot) : SettingsBandShotBase(shot, 641) {
    companion object { @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}") fun params() = SettingsBandShots.params() }
}
