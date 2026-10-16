package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.tether.app.client.Handover
import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.AgentSession
import com.tether.app.ui.components.FixedKeyboardInset
import com.tether.app.ui.components.LocalKeyboardInset
import com.tether.app.ui.components.TetherLayoutClass
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.shell.EmptyStage
import com.tether.app.ui.shell.ExpandedShellUnderTest
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W34 goldens (A-spec section 5 #4-#12): the New Session sheet in handover mode and the Session name row, at the phone
 * (412x915 @420dpi, the docked sheet) and the expanded card (1280x800), both Studio skins. Every state is seeded through
 * the real engine on an unconfined scope; the client throws on anything the golden did not mean to send.
 */
enum class HandoverComposerShot(val id: String, val phone: Boolean, val expanded: Boolean) {
    Default("handover-composer", true, true),
    NameCleared("handover-composer-name-cleared", true, true),
    CodexCustom("handover-composer-codex-custom", true, true),
    Pi("handover-composer-pi", true, false),
    Pending("handover-composer-pending", true, false),
    Refusal("handover-composer-refusal", true, true),
    Blocked("handover-composer-blocked", true, false),
    NameEmpty("new-session-name-empty", true, true),
    NameTyped("new-session-name-typed", true, false),
}

private val exact = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private val SOURCE = AgentSession(
    id = "src-1", provider = "claude", name = "Parser rewrite", cwd = "/srv/ws/parity-app", status = "ready",
    startedAt = 1, updatedAt = 1, handoverSuccessorName = JsonPrimitive("Parser rewrite 2"),
)

/** The seeded composer for [shot]: what the sheet would show. */
class HandoverComposerSeed(shot: HandoverComposerShot) {
    private val job = Job()
    val client = DraftTestClient(failOnSend = false).apply {
        serverProtocolVersion.value = 151
        providerCatalog.value = DraftFixtures.catalog.map {
            if (it.key == "pi") it.copy(status = "ready", models = listOf(SessionModelOption("m1", "Model 1"))) else it
        }
        providers.value = DraftFixtures.providers.map { if (it.id == "pi") it.copy(available = true) else it }
    }
    private val model = DraftComposerModel(
        client = client,
        draftStore = InMemoryDraftStore(),
        scope = CoroutineScope(Dispatchers.Unconfined + job),
        currentWorkspace = { null },
        newRequestId = { "req-1" },
    )
    val state: DraftComposerState
    private val readiness: String

    init {
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        val source = when (shot) {
            HandoverComposerShot.CodexCustom -> SOURCE.copy(provider = "codex", model = "m1", sandboxPolicy = "off", approvalPolicy = "on-request")
            HandoverComposerShot.Pi -> SOURCE.copy(provider = "pi", model = "m1")
            else -> SOURCE
        }
        when (shot) {
            HandoverComposerShot.NameEmpty -> Unit
            HandoverComposerShot.NameTyped -> {
                model.selectProviderAndModel("work", "m1")
                model.setName("Release notes")
                model.setText("Summarize the README, then list the open issues that mention the sidebar.")
            }
            else -> check(model.openHandover(source)) { "the source's engine is in the catalog" }
        }
        if (shot == HandoverComposerShot.NameCleared) model.setName("")
        if (shot == HandoverComposerShot.Pending) check(model.submitHandover() == com.tether.app.client.HandoverSubmitResult.Sent)
        readiness = model.readiness()
        state = model.state.value.let { s ->
            val h = s.handover
            when {
                shot == HandoverComposerShot.CodexCustom -> check(h?.codexCurrentOption != null) { "the triple matches no preset" }.let { s }
                shot == HandoverComposerShot.Refusal -> s.copy(error = Handover.refusalCopy("folder_unavailable"))
                shot == HandoverComposerShot.Blocked && h != null -> s.copy(handover = h.copy(blockedLine = Handover.blockedCopy("Parser rewrite", "handover_pending")))
                else -> s
            }
        }
    }

    fun inputs() = DraftSheetInputs(
        draft = state,
        browser = draftBrowserInputs(state, client.providerCatalog.value, client.providers.value, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 1_700_000_000_000L),
        quickPicks = workspaceQuickPicks(listOf("/srv/ws/parity-app"), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
        workspaceRoot = DraftFixtures.ROOT,
        readiness = if (state.handover != null) "" else readiness,
        options = com.tether.app.client.DraftSessionOptionsModel.of(state),
        v151 = true,
    )

    fun close() = job.cancel()
}

fun ComposeContentTestRule.snapHandoverComposer(shot: HandoverComposerShot, skin: TetherSkin, phone: Boolean, settingsMode: Boolean = false) {
    mainClock.autoAdvance = false
    val seed = HandoverComposerSeed(shot)
    val layout = if (phone) TetherLayoutClass.Phone else TetherLayoutClass.Expanded
    setContent {
        val slots = if (phone) placeholderSlots() else expandedSlots()
        val stage = EmptyStage.Welcome(connected = true, providers = ShellFixtures.providers)
        Box(Modifier.fillMaxSize()) {
            if (phone) ShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots)
            else ExpandedShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots)
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalKeyboardInset provides FixedKeyboardInset(0.dp)) {
                    if (settingsMode) {
                        DraftSettingsFrame(
                            seed.inputs(),
                            DraftSettingsState(open = true, view = DraftSettingsView.Mode),
                            DraftSettingsActions(
                                browser = ModelBrowserActions(onSelect = { _, _ -> }, onRetry = {}, onAddModel = { _, _ -> true }, onRemoveModel = { _, _ -> }, onClose = {}),
                                onSelectEffort = { true }, onSelectMode = { _, _ -> true }, onToggleAuto = { true }, onClose = {},
                            ),
                            layout,
                        )
                    } else {
                        DraftComposerFrame(seed.inputs(), DraftSheetActions(), layout = layout)
                    }
                }
            }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/${shot.id}/${skin.id}-${if (phone) "phone" else "expanded"}.png", roborazziOptions = exact)
    seed.close()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HandoverComposerPhoneScreenshotTest(private val shot: HandoverComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    // The phone's Mode view is the settings sheet, so the codex-custom golden shoots it open on Mode.
    @Test fun sheet() = rule.snapHandoverComposer(shot, skin, phone = true, settingsMode = shot == HandoverComposerShot.CodexCustom)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HandoverComposerShot.entries.filter { it.phone }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class HandoverComposerExpandedScreenshotTest(private val shot: HandoverComposerShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapHandoverComposer(shot, skin, phone = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = HandoverComposerShot.entries.filter { it.expanded }.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
