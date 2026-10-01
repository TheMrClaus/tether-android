package com.tether.app.ui.settings

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
 * its read-only sync rows, `-engines-loading` / `-blocked` / `-error` the other states.
 */
enum class SettingsShot(
    val id: String,
    val tab: SettingsTab,
    val restart: Boolean = false,
    val accounts: AccountsShot? = null,
    val server: ServerShot? = null,
) {
    General("settings-general", SettingsTab.General),
    Appearance("settings-appearance", SettingsTab.Appearance),
    Devices("settings-devices", SettingsTab.Devices),
    Engines("settings-engines", SettingsTab.Engines, accounts = AccountsShot.Loaded),
    EnginesSync("settings-engines-sync", SettingsTab.Engines, accounts = AccountsShot.Sync),
    EnginesLoading("settings-engines-loading", SettingsTab.Engines, accounts = AccountsShot.Loading),
    EnginesBlocked("settings-engines-blocked", SettingsTab.Engines, accounts = AccountsShot.Blocked),
    EnginesError("settings-engines-error", SettingsTab.Engines, accounts = AccountsShot.Error),
    Restart("settings-restart", SettingsTab.General, restart = true),
    Advanced("settings-advanced", SettingsTab.Advanced, server = ServerShot.Advanced),
    AdvancedRevealed("settings-advanced-revealed", SettingsTab.Advanced, server = ServerShot.Revealed),
    AdvancedLocked("settings-advanced-locked", SettingsTab.Advanced, server = ServerShot.Locked),
    AdvancedLifecycle("settings-advanced-lifecycle", SettingsTab.Advanced, server = ServerShot.Lifecycle),
    AdvancedDefaults("settings-advanced-defaults", SettingsTab.Advanced, server = ServerShot.Defaults),
    AdvancedCli("settings-advanced-cli", SettingsTab.Advanced, server = ServerShot.Cli),
    Metadata("settings-metadata", SettingsTab.Metadata, server = ServerShot.Metadata),
    MetadataText("settings-metadata-text", SettingsTab.Metadata, server = ServerShot.MetadataText),
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
    ;

    fun binding(): ServerSettingsBinding = when (this) {
        Locked -> ServerFixtures.binding(
            view = ServerFixtures.view(envForced = mapOf("host" to true, "port" to true, "password" to true, "stateDir" to true, "workspaceRoot" to true)),
            advanced = ServerFixtures.ADVANCED_FORCED,
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
    ;

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
            Loaded, Sync -> loaded
            Loading -> ClaudeAccountsState(origin = origin)
            Blocked -> ClaudeAccountsState(origin = origin, listFault = AccountsFault.Blocked(302))
            Error -> ClaudeAccountsState(origin = origin, listFault = AccountsFault.Unavailable(500))
        }
    }

    fun binding() = ClaudeAccountsBinding(NeverAsked, AccountsFixtures.ORIGIN, AccountsFixtures.TIME, initial = seed())
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
    setContent {
        SettingsUnderTest(
            store.prefs,
            state,
            mode = skin.mode,
            layout = if (size == "tablet") TetherLayoutClass.Expanded else TetherLayoutClass.Phone,
            restartRequired = shot.restart,
            initialPreferences = stored,
            claudeAccounts = shot.accounts?.binding() ?: ClaudeAccountsBinding.None,
            serverSettings = shot.server?.binding() ?: ServerSettingsBinding.None,
        )
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot.server?.reveal == true) {
        onNodeWithTag(ServerSettingsTags.reveal(ServerSetting.Password), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }
    (shot.accounts?.scrollTo ?: shot.server?.scrollTo)?.let { scrollTo ->
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

/** PLAN §4: 1.3× font scale does not break the dialog (General, Appearance, the Claude accounts list, Advanced and Metadata; Studio light + dark). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class SettingsFontScaleScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) : SettingsShotBase() {
    @Test fun settings() = rule.snapSettings(store, shot, skin, "phone", name = "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(SettingsShot.General, SettingsShot.Appearance, SettingsShot.Engines, SettingsShot.Advanced, SettingsShot.Metadata).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
