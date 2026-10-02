package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.DraftComposerState
import com.tether.app.client.NewSessionGuard
import com.tether.app.client.StagedAttachment
import com.tether.app.protocol.Attachment
import com.tether.app.ui.components.FixedKeyboardInset
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.shell.EmptyStage
import com.tether.app.ui.shell.ExpandedShellUnderTest
import com.tether.app.ui.shell.PhoneShellSlots
import com.tether.app.ui.shell.PhoneShellState
import com.tether.app.ui.shell.ShellFixtures
import com.tether.app.ui.shell.ShellUnderTest
import com.tether.app.ui.shell.choiceFor
import com.tether.app.ui.shell.expandedSlots
import com.tether.app.ui.shell.placeholderSlots
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-abm: the new-session sheet's states, Studio light and dark, at the web's phone viewport and its
 * tablet one. `empty` = a cold draft (the folder seeded from the workspace root, no provider picked:
 * readiness asks for a message); `ready` = a profile row picked, a prompt typed and an attachment
 * staged (Send enabled); `keyboard` = the same with a 300dp keyboard up (the text box shrinks, the
 * controls lift); `error` = the server refused the create (its words, the prompt kept); `launching` =
 * Send pressed, the sheet gone and the hand-off stage on the shell's own stage.
 *
 * Every state is seeded synchronously through the real engine on an unconfined scope, the clock is
 * driven by hand, and the client throws on any send (a golden that sends anything fails).
 */
/**
 * ta-2uq: the model browser over the sheet. `all` = the provider rows; `provider` = one profile row's
 * models with its pick checked; `error` = a row whose fetch failed (its words, Retry); `settings` = the
 * cog panel (Discovered, a custom id, Updated 12m ago, Refresh); `empty` = a live catalog offering nothing.
 */
enum class BrowserShot(val id: String) {
    All("model-browser-all"),
    Provider("model-browser-provider"),
    Error("model-browser-error"),
    Settings("model-browser-settings"),
    Empty("model-browser-empty"),
}

/** The goldens' clock (display only: "Updated …"). */
private const val NOW = 1_700_000_000_000L

/**
 * ta-xki: Effort and Mode per provider. Phone: the settings sheet trigger row (the model chip and the
 * sliders chip, which names an elevated mode); tablet: the live row. `claude` = Claude on its default
 * Auto with effort High; `codex` = the Auto-review preset, effort untouched; `opencode` = Build with
 * the Auto chip on.
 */
enum class OptionsShot(val id: String) {
    Claude("draft-options-claude"),
    Codex("draft-options-codex"),
    Opencode("draft-options-opencode"),
}

/**
 * ta-xki: the phone's settings sheet over the draft. `hub` = Claude's (Model, Effort High, Mode Auto);
 * `codex-mode` = Codex's three presets; `opencode-mode` = Build / Plan and the Auto row, Auto on.
 */
enum class SettingsShot(val id: String, val options: OptionsShot, val view: DraftSettingsView) {
    Hub("draft-settings-hub", OptionsShot.Claude, DraftSettingsView.Root),
    CodexMode("draft-settings-codex-mode", OptionsShot.Codex, DraftSettingsView.Mode),
    OpencodeMode("draft-settings-opencode-mode", OptionsShot.Opencode, DraftSettingsView.Mode),
}

enum class DraftShot(val id: String) {
    Empty("draft-empty"),
    Ready("draft-ready"),
    Keyboard("draft-keyboard"),
    Error("draft-error"),
    Launching("draft-launching"),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private const val REFUSAL = "Skipping tool approvals needs a browser sign-in, not a paired device."
private const val PROMPT = "Summarize the README, then list the open issues that mention the sidebar."

/** The seeded draft, through the engine: what the sheet would show for [shot]. */
private class Seed(shot: DraftShot, val browser: BrowserShot? = null, val options: OptionsShot? = null) {
    private val job = Job()
    val client = DraftTestClient(failOnSend = true).apply {
        when (browser) {
            BrowserShot.Empty -> providerCatalog.value = emptyList()
            BrowserShot.Error -> providerCatalog.value = DraftFixtures.catalog.map {
                if (it.key == "codex") it.copy(status = "error", models = emptyList(), error = "codex app-server did not answer in time.", fetchedAt = NOW - 30_000) else it
            }
            BrowserShot.Settings -> providerCatalog.value = DraftFixtures.catalog.map { if (it.key == "claude") it.copy(fetchedAt = NOW - 12 * 60_000) else it }
            else -> if (options != null) providerCatalog.value = OptionsFixtures.catalog
        }
    }
    val model = DraftComposerModel(
        client = client,
        draftStore = InMemoryDraftStore(),
        scope = CoroutineScope(Dispatchers.Unconfined + job),
        currentWorkspace = { null },
    )
    val state: DraftComposerState
    val readiness: String

    init {
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        if (browser == BrowserShot.Settings) model.addCustomModel("claude", "claude-opus-4-5[1m]")
        if (options != null) {
            // ta-xki: picked through the engine as the controls would.
            when (options) {
                OptionsShot.Claude -> {
                    model.selectProviderAndModel("claude", "m1")
                    check(model.selectEffort("high"))
                }
                OptionsShot.Codex -> {
                    model.selectProviderAndModel("codex", "m1")
                    check(model.selectMode("auto-review", "codex"))
                }
                OptionsShot.Opencode -> {
                    model.selectProviderAndModel("opencode", "m1")
                    check(model.toggleAuto("opencode"))
                }
            }
            model.setText(PROMPT)
        } else if ((shot != DraftShot.Empty || browser != null) && browser != BrowserShot.Empty) {
            // ta-2uq: picked through the Model chip: the row and its model together.
            model.selectProviderAndModel("work", "m1")
            model.setText(PROMPT)
        }
        if (shot == DraftShot.Ready || shot == DraftShot.Keyboard || shot == DraftShot.Launching) {
            model.setStagedAttachments(listOf(StagedAttachment(1, Attachment(name = "sidebar-notes.md", mediaType = "text/markdown", data = "aGVsbG8="), 2_458)))
        }
        readiness = model.readiness()
        state = model.state.value.let { if (shot == DraftShot.Error) it.copy(error = REFUSAL) else it }
    }

    fun browserInputs() = draftBrowserInputs(state, client.providerCatalog.value, DraftFixtures.providers, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), NOW)

    fun browserState() = when (browser) {
        BrowserShot.Provider -> ModelBrowserState(open = true, view = BrowserView.Provider("work"))
        BrowserShot.Error -> ModelBrowserState(open = true, view = BrowserView.Provider("codex"))
        BrowserShot.Settings -> ModelBrowserState(open = true, view = BrowserView.Provider("claude"), settingsOpen = true)
        else -> ModelBrowserState(open = true)
    }

    fun inputs() = DraftSheetInputs(
        draft = state,
        browser = browserInputs(),
        browserOpen = browser != null,
        quickPicks = workspaceQuickPicks(listOf("/srv/ws/parity-app"), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
        workspaceRoot = DraftFixtures.ROOT,
        readiness = readiness,
        options = com.tether.app.client.DraftSessionOptionsModel.of(state),
    )

    fun close() = job.cancel()
}

private fun noSend(what: String): Nothing = throw AssertionError("a golden must not $what")

private val failingActions = DraftSheetActions(
    onClose = { noSend("close") },
    onText = { noSend("type") },
    onModelChip = { noSend("open the browser") },
    onPickFolder = { noSend("pick a folder") },
    onBrowse = { noSend("browse") },
    onAttach = { noSend("attach") },
    onRemoveAttachment = { noSend("remove") },
    onSubmit = { noSend("send") },
    onSelectEffort = { noSend("pick an effort") },
    onSelectMode = { _, _ -> noSend("pick a mode") },
    onToggleAuto = { noSend("toggle Auto") },
    onOpenSettings = { noSend("open the settings sheet") },
)

private val failingSettingsActions = DraftSettingsActions(
    browser = ModelBrowserActions(
        onSelect = { _, _ -> noSend("pick a model") },
        onRetry = { noSend("retry") },
        onAddModel = { _, _ -> noSend("add a model") },
        onRemoveModel = { _, _ -> noSend("remove a model") },
        onClose = { noSend("close the browser") },
    ),
    onSelectEffort = { noSend("pick an effort") },
    onSelectMode = { _, _ -> noSend("pick a mode") },
    onToggleAuto = { noSend("toggle Auto") },
    onClose = { noSend("close the sheet") },
)

private fun launchingSlots(phone: Boolean, seed: Seed): PhoneShellSlots {
    val base = if (phone) placeholderSlots() else expandedSlots()
    return PhoneShellSlots(
        drawer = base.drawer,
        chat = base.chat,
        inspector = base.inspector,
        gauge = base.gauge,
        dial = base.dial,
        launching = { DraftLaunching(seed.state.text, seed.state.attachments, providerLabel = "Claude (work)") },
    )
}

private val failingBrowserActions = ModelBrowserActions(
    onSelect = { _, _ -> noSend("pick a model") },
    onRetry = { noSend("retry") },
    onAddModel = { _, _ -> noSend("add a model") },
    onRemoveModel = { _, _ -> noSend("remove a model") },
    onClose = { noSend("close the browser") },
)

fun ComposeContentTestRule.snapDraft(
    shot: DraftShot,
    skin: TetherSkin,
    phone: Boolean,
    name: String,
    browser: BrowserShot? = null,
    options: OptionsShot? = null,
    settings: SettingsShot? = null,
) {
    mainClock.autoAdvance = false
    val seed = Seed(shot, browser, options ?: settings?.options)
    val layout = if (phone) TetherLayoutClass.Phone else TetherLayoutClass.Expanded
    setContent {
        val shell: @Composable () -> Unit = {
            val slots = if (shot == DraftShot.Launching) launchingSlots(phone, seed) else if (phone) placeholderSlots() else expandedSlots()
            val stage = EmptyStage.Welcome(connected = true, providers = ShellFixtures.providers)
            if (phone) ShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots) else ExpandedShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots)
        }
        Box(Modifier.fillMaxSize()) {
            shell()
            if (shot != DraftShot.Launching) {
                TetherTheme(choiceFor(skin)) {
                    CompositionLocalProvider(
                        LocalReducedMotion provides true,
                        LocalKeyboardInset provides FixedKeyboardInset(if (shot == DraftShot.Keyboard) 300.dp else 0.dp),
                    ) {
                        DraftComposerFrame(seed.inputs(), failingActions, layout = layout)
                        when {
                            // ta-xki: below 64rem the model chip opens the settings sheet on the browser.
                            browser != null && phone -> DraftSettingsFrame(
                                seed.inputs(),
                                DraftSettingsState(open = true, view = DraftSettingsView.Model, browser = seed.browserState()),
                                failingSettingsActions,
                                layout,
                            )
                            browser != null -> ModelBrowserFrame(seed.browserInputs(), seed.browserState(), failingBrowserActions, layout)
                            settings != null -> DraftSettingsFrame(seed.inputs(), DraftSettingsState(open = true, view = settings.view), failingSettingsActions, layout)
                        }
                    }
                }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name/${skin.id}-${if (phone) "phone" else "tablet"}.png", roborazziOptions = exact)
    seed.close()
}

/** Every state × both Studio skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftComposerPhoneScreenshotTest(private val shot: DraftShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapDraft(shot, skin, phone = true, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = DraftShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** Every state × both Studio skins at the web's tablet viewport (1280×800 @1x): the centred dialog. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DraftComposerExpandedScreenshotTest(private val shot: DraftShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapDraft(shot, skin, phone = false, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = DraftShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale (Studio light + dark) on a phone: the cold draft and the ready one. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class DraftComposerFontScaleScreenshotTest(private val shot: DraftShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapDraft(shot, skin, phone = true, "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(DraftShot.Empty, DraftShot.Ready).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-2uq: every browser state × both Studio skins on a phone (the panel docked at the foot). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ModelBrowserPhoneScreenshotTest(private val shot: BrowserShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun browser() = rule.snapDraft(DraftShot.Empty, skin, phone = true, shot.id, browser = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = BrowserShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-2uq: every browser state × both Studio skins on a tablet (the centred card). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ModelBrowserExpandedScreenshotTest(private val shot: BrowserShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun browser() = rule.snapDraft(DraftShot.Empty, skin, phone = false, shot.id, browser = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = BrowserShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-2uq, PLAN §4: 1.3× font scale on a phone: the provider list and the settings panel. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class ModelBrowserFontScaleScreenshotTest(private val shot: BrowserShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun browser() = rule.snapDraft(DraftShot.Empty, skin, phone = true, "${shot.id}-font-1.3x", browser = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(BrowserShot.All, BrowserShot.Settings).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-xki: Effort and Mode per provider × both Studio skins on a phone (the settings trigger row). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftOptionsPhoneScreenshotTest(private val shot: OptionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun options() = rule.snapDraft(DraftShot.Empty, skin, phone = true, shot.id, options = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = OptionsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-xki: Effort and Mode per provider × both Studio skins on a tablet (the live row). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class DraftOptionsExpandedScreenshotTest(private val shot: OptionsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun options() = rule.snapDraft(DraftShot.Empty, skin, phone = false, shot.id, options = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = OptionsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-xki: the phone's settings sheet (hub, Codex presets, opencode with Auto) × both Studio skins. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftSettingsPhoneScreenshotTest(private val shot: SettingsShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapDraft(DraftShot.Empty, skin, phone = true, shot.id, settings = shot)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettingsShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** ta-xki, PLAN §4: 1.3× font scale on a phone: Claude's trigger row and the settings hub. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class DraftOptionsFontScaleScreenshotTest(private val shot: String, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun options() = when (shot) {
        "options" -> rule.snapDraft(DraftShot.Empty, skin, phone = true, "${OptionsShot.Claude.id}-font-1.3x", options = OptionsShot.Claude)
        else -> rule.snapDraft(DraftShot.Empty, skin, phone = true, "${SettingsShot.Hub.id}-font-1.3x", settings = SettingsShot.Hub)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf("options", "settings").flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
