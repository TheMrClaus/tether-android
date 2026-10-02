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
) {
    General("settings-general", SettingsTab.General),
    Appearance("settings-appearance", SettingsTab.Appearance),
    Devices("settings-devices", SettingsTab.Devices, devices = DevicesShot.Top),
    DevicesSessions("settings-devices-sessions", SettingsTab.Devices, devices = DevicesShot.Sessions),
    DevicesPaired("settings-devices-paired", SettingsTab.Devices, devices = DevicesShot.Paired),
    DevicesSelf("settings-devices-self", SettingsTab.Devices, devices = DevicesShot.Self),
    DevicesTokenPassword("settings-devices-token-password", SettingsTab.Devices, devices = DevicesShot.TokenPassword),
    DevicesCode("settings-devices-code", SettingsTab.Devices, devices = DevicesShot.Code),
    DevicesCodeRevealed("settings-devices-code-revealed", SettingsTab.Devices, devices = DevicesShot.CodeRevealed),
    DevicesCodeExpired("settings-devices-code-expired", SettingsTab.Devices, devices = DevicesShot.CodeExpired),
    DevicesOwner("settings-devices-owner", SettingsTab.Devices, devices = DevicesShot.Owner),
    DevicesChecking("settings-devices-checking", SettingsTab.Devices, devices = DevicesShot.Checking),
    DevicesError("settings-devices-error", SettingsTab.Devices, devices = DevicesShot.Error),
    DevicesPasskeyEmpty("settings-devices-passkey-empty", SettingsTab.Devices, devices = DevicesShot.PasskeyEmpty),
    DevicesPasskeyAdding("settings-devices-passkey-adding", SettingsTab.Devices, devices = DevicesShot.PasskeyAdding),
    DevicesPasskeyDuplicate("settings-devices-passkey-duplicate", SettingsTab.Devices, devices = DevicesShot.PasskeyDuplicate),
    DevicesPasskeyAdded("settings-devices-passkey-added", SettingsTab.Devices, devices = DevicesShot.PasskeyAdded),
    Engines("settings-engines", SettingsTab.Engines, accounts = AccountsShot.Loaded),
    EnginesSync("settings-engines-sync", SettingsTab.Engines, accounts = AccountsShot.Sync),
    EnginesLoading("settings-engines-loading", SettingsTab.Engines, accounts = AccountsShot.Loading),
    EnginesBlocked("settings-engines-blocked", SettingsTab.Engines, accounts = AccountsShot.Blocked),
    EnginesError("settings-engines-error", SettingsTab.Engines, accounts = AccountsShot.Error),
    EnginesOwner("settings-engines-owner", SettingsTab.Engines, accounts = AccountsShot.Owner),
    EnginesLogin("settings-engines-login", SettingsTab.Engines, accounts = AccountsShot.Login),
    EnginesAdding("settings-engines-adding", SettingsTab.Engines, accounts = AccountsShot.Adding),
    EnginesRename("settings-engines-rename", SettingsTab.Engines, accounts = AccountsShot.Rename),
    Restart("settings-restart", SettingsTab.General, restart = true),
    Advanced("settings-advanced", SettingsTab.Advanced, server = ServerShot.Advanced),
    AdvancedRevealed("settings-advanced-revealed", SettingsTab.Advanced, server = ServerShot.Revealed),
    AdvancedLocked("settings-advanced-locked", SettingsTab.Advanced, server = ServerShot.Locked),
    AdvancedLifecycle("settings-advanced-lifecycle", SettingsTab.Advanced, server = ServerShot.Lifecycle),
    AdvancedDefaults("settings-advanced-defaults", SettingsTab.Advanced, server = ServerShot.Defaults),
    AdvancedCli("settings-advanced-cli", SettingsTab.Advanced, server = ServerShot.Cli),
    Metadata("settings-metadata", SettingsTab.Metadata, server = ServerShot.Metadata),
    MetadataText("settings-metadata-text", SettingsTab.Metadata, server = ServerShot.MetadataText),
    EnginesCards("settings-engines-cards", SettingsTab.Engines, server = ServerShot.Engines),
    EnginesMissing("settings-engines-missing", SettingsTab.Engines, server = ServerShot.EnginesMissing),
    EnginesLocked("settings-engines-locked", SettingsTab.Engines, server = ServerShot.EnginesLocked),
    Profiles("settings-profiles", SettingsTab.Engines, profiles = ProfilesShot.Loaded),
    ProfilesEnv("settings-profiles-env", SettingsTab.Engines, profiles = ProfilesShot.Masked),
    ProfilesRevealed("settings-profiles-revealed", SettingsTab.Engines, profiles = ProfilesShot.Revealed),
    Nodes("settings-nodes", SettingsTab.Nodes, nodes = NodesShot.List),
    NodesEmpty("settings-nodes-empty", SettingsTab.Nodes, nodes = NodesShot.Empty),
    NodesAdding("settings-nodes-adding", SettingsTab.Nodes, nodes = NodesShot.Adding),
    NodesRevealed("settings-nodes-revealed", SettingsTab.Nodes, nodes = NodesShot.Revealed),
    NodesResult("settings-nodes-result", SettingsTab.Nodes, nodes = NodesShot.Result),
    NodesError("settings-nodes-error", SettingsTab.Nodes, nodes = NodesShot.Error),
}

/**
 * T10.3: the Nodes seeds, timing-free like the others: the list and the last answer are built HERE
 * and handed in (the answer as the actions' initial notice), so the first frame is the drawn tab;
 * the writer behind them fails the shot on any request. `settings-nodes` the list (every status,
 * the skew warning), `-empty` no nodes, `-adding` the form filled with the credential masked,
 * `-revealed` the same shown (an obviously FAKE bundle), `-result` a probe's answer, `-error` the
 * refusal a phone sign-in gets before tether #236 is deployed. The form is filled by synchronous
 * semantics actions on the hand-driven clock, then the focus cleared (no cursor in the shot).
 */
enum class NodesShot(
    val scrollTo: String?,
    val list: List<com.tether.app.protocol.NodeSummary> = NodeFixtures.LIST,
    val fill: Boolean = false,
    val reveal: Boolean = false,
    val notice: NodeNotice? = null,
) {
    List(NodeTags.row("node_ws")),
    Empty(NodeTags.Add, list = emptyList()),
    Adding(null, fill = true),
    Revealed(null, fill = true, reveal = true),
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
 * password switch a device-token sign-in cannot turn off (r2, security F6),
 * `-code` a fresh code masked, `-code-revealed` the same shown (an obviously FAKE code; the one tap
 * of the shot, a synchronous state change on the hand clock), `-code-expired` the expired card,
 * `-owner` the owner-grade refusal before tether #236 is deployed, `-checking` the opening reads in
 * flight, `-error` a refusal in each area. T10.5 (the panel's passkey prompt available, as on a
 * device): `-passkey-empty` none yet, the add row ready (the web reference's own state),
 * `-passkey-adding` the ceremony in flight ("Adding…", every key held), `-passkey-duplicate`
 * the web's words for an authenticator that already has one, `-passkey-added` the notice and the new
 * row after the re-read.
 */
enum class DevicesShot(val scrollTo: String?, val reveal: Boolean = false) {
    Top(null),
    Sessions(DevicesTags.Sessions),
    Paired(DevicesTags.Paired),
    Self(DevicesTags.Paired),
    TokenPassword(DevicesTags.PasswordToggle),
    Code(DevicesTags.Paired),
    CodeRevealed(DevicesTags.Paired, reveal = true),
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
        Code, CodeRevealed -> DevicesFixtures.seed(code = DevicesFixtures.code(DevicesFixtures.FAKE_CODE))
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
 * acp card), `-env` the env editor with its value masked, `-revealed` the same with the value shown
 * (an obviously FAKE key; the one tap of the shot, a synchronous state change on the hand clock).
 */
enum class ProfilesShot(val scrollTo: String, val reveal: Boolean = false) {
    Loaded(ProfileTags.Section),
    Masked(ProfileTags.row("gemini", ProfileTags.ENV)),
    Revealed(ProfileTags.row("gemini", ProfileTags.ENV), reveal = true),
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

/** The writer behind a seeded shot: a write is a timing dependency (and a bug), so it fails the shot. */
private object NeverWrites : ServerSettingsWriter {
    override fun patch(patch: JsonObject, origin: String): Boolean = error("a seeded shot must not write")
    override fun cliVersion(message: ClientMessage.SetAdvancedSettings, origin: String): Boolean = error("a seeded shot must not write")
    override fun detectEngines(origin: String): Boolean = error("a seeded shot must not scan")
    override fun confirmed(write: com.tether.app.client.ConfirmedEngineWrite, origin: String): Boolean = error("a seeded shot must not write")
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
    /** ta-7rh: the nickname row open with a name typed, and the server's refusal under it. */
    Adding(ClaudeAccountsTags.AddField),
    /** ta-7rh: Rename open on "work" (filled with the nickname), and a removal's outcome under another card. */
    Rename(ClaudeAccountsTags.card("claude-work")),
    ;

    /** The changes' state of the shot (null: none; the reads' seed alone). */
    fun writeSeed(): AccountsWriteSeed? = when (this) {
        Owner -> AccountsWriteSeed(ownerNeeded = true)
        Login -> AccountsWriteSeed(
            logins = mapOf("claude-work" to LoginPanel(com.tether.app.client.ClaudeLoginStatus.AwaitingCode, com.tether.app.client.ClaudeLoginLink.parse("https://claude.ai/oauth/authorize?code=true&client_id=FAKE&state=FAKE"))),
        )
        Adding -> AccountsWriteSeed(adding = true, addText = "home", line = AccountsLine(ClaudeAccountsCopy.ADD_FAILED, error = true))
        Rename -> AccountsWriteSeed(
            renaming = "claude-work",
            renameText = "work",
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
            Loaded, Sync, Owner, Login, Adding, Rename -> loaded
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
}

fun ComposeContentTestRule.snapSettings(store: PrefsStore, shot: SettingsShot, skin: TetherSkin, size: String, name: String = shot.id) {
    // The stored mode is the skin shown (the web reference's seeded state), so Appearance agrees.
    store.seed(PreferenceKeys.THEME_MODE to skin.mode.id)
    // Timing-free by construction (T10.1 r4): the stored preferences are read HERE, and the frame
    // gets them as its live value and its seeded draft, so its first frame is already the loaded
    // dialog (the not-loaded state never renders). Nothing in it animates from a start value then:
    // keys, switches and choice rows compose at their resting look. The clock is driven by hand.
    val stored = runBlocking { store.prefs.preferences.first() }
    val state = SettingsDialogState(shot.tab, GeneralDraft.of(stored))
    mainClock.autoAdvance = false
    var focus: androidx.compose.ui.focus.FocusManager? = null
    setContent {
        focus = androidx.compose.ui.platform.LocalFocusManager.current
        val nodeActions = shot.nodes?.let { rememberNodesActions(NeverWritesNodes, it.notice) }
        val devicesController = shot.devices?.let { rememberDevicesController(NeverCalledSecurity, DevicesFixtures.ORIGIN, it.seed(), now = { DevicesFixtures.NOW }, authenticator = NeverPromptsPasskeys) }
        SettingsUnderTest(
            store.prefs,
            state,
            mode = skin.mode,
            layout = if (size == "tablet") TetherLayoutClass.Expanded else TetherLayoutClass.Phone,
            restartRequired = shot.restart,
            initialPreferences = stored,
            claudeAccounts = shot.accounts?.binding() ?: ClaudeAccountsBinding.None,
            serverSettings = shot.server?.binding() ?: ServerSettingsBinding.None,
            providers = shot.profiles?.binding() ?: ProvidersBinding.None,
            nodes = if (shot.nodes != null && nodeActions != null) shot.nodes.binding(nodeActions) else NodesBinding.None,
            devices = devicesController?.let { DevicesBinding(it, now = { DevicesFixtures.NOW }) } ?: DevicesBinding.None,
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
        onNodeWithTag(NodeTags.CredentialReveal, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithTag(NodeTags.Credential, useUnmergedTree = true).performTextReplacement(NodeFixtures.FAKE_CREDENTIAL)
        if (!shot.nodes.reveal) onNodeWithTag(NodeTags.CredentialReveal, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        runOnIdle { focus?.clearFocus(force = true) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot.server?.reveal == true) {
        onNodeWithTag(ServerSettingsTags.reveal(ServerSetting.Password), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot.devices?.reveal == true) {
        onNodeWithTag(DevicesTags.CodeReveal, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    if (shot.profiles?.reveal == true) {
        onNodeWithTag(ProfileTags.envReveal("gemini", "GEMINI_API_KEY"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
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

/** PLAN §4: 1.3× font scale does not break the dialog (General, Appearance, the Claude accounts list and a login (ta-7rh), Advanced, Metadata, the engine cards, the custom providers, the Nodes list and form, and the Devices top, paired devices and pairing code; Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SettingsFontScaleScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone", name = "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SettingsShot.General, SettingsShot.Appearance, SettingsShot.Engines, SettingsShot.EnginesLogin, SettingsShot.Advanced, SettingsShot.Metadata, SettingsShot.EnginesCards, SettingsShot.Profiles, SettingsShot.Nodes, SettingsShot.NodesAdding, SettingsShot.Devices, SettingsShot.DevicesPaired, SettingsShot.DevicesCode).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
