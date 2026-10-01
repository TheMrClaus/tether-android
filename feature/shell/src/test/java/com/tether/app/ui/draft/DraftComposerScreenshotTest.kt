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
private class Seed(shot: DraftShot) {
    private val job = Job()
    val client = DraftTestClient(failOnSend = true)
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
        if (shot != DraftShot.Empty) {
            model.selectProvider("work")
            model.setText(PROMPT)
        }
        if (shot == DraftShot.Ready || shot == DraftShot.Keyboard || shot == DraftShot.Launching) {
            model.setStagedAttachments(listOf(StagedAttachment(1, Attachment(name = "sidebar-notes.md", mediaType = "text/markdown", data = "aGVsbG8="), 2_458)))
        }
        readiness = model.readiness()
        state = model.state.value.let { if (shot == DraftShot.Error) it.copy(error = REFUSAL) else it }
    }

    fun inputs() = DraftSheetInputs(
        draft = state,
        rows = NewSessionGuard.rows(DraftFixtures.catalog, DraftFixtures.providers),
        providers = DraftFixtures.providers,
        catalogPending = false,
        quickPicks = workspaceQuickPicks(listOf("/srv/ws/parity-app"), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
        workspaceRoot = DraftFixtures.ROOT,
        readiness = readiness,
    )

    fun close() = job.cancel()
}

private fun noSend(what: String): Nothing = throw AssertionError("a golden must not $what")

private val failingActions = DraftSheetActions(
    onClose = { noSend("close") },
    onText = { noSend("type") },
    onPickProvider = { noSend("pick") },
    onPickFolder = { noSend("pick a folder") },
    onBrowse = { noSend("browse") },
    onAttach = { noSend("attach") },
    onRemoveAttachment = { noSend("remove") },
    onSubmit = { noSend("send") },
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

fun ComposeContentTestRule.snapDraft(shot: DraftShot, skin: TetherSkin, phone: Boolean, name: String) {
    mainClock.autoAdvance = false
    val seed = Seed(shot)
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
