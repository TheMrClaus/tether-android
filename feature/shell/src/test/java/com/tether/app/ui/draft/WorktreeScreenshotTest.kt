package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.DraftComposerState
import com.tether.app.client.DraftSessionOptionsModel
import com.tether.app.client.WorktreeDeclaredScript
import com.tether.app.client.WorktreeField
import com.tether.app.client.WorktreeSourceInfo
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-23f (T8.1 slice 5): the worktree states of the new-session sheet, Studio light and dark, at the
 * web's phone and tablet viewports (and 1.3× on a phone).
 *
 * - `branch-off`: New branch, the repo's default base as the placeholder, the setup + scripts note;
 * - `existing-branch`: Existing branch with a branch typed (Send ready);
 * - `pr`: Pull request #42 with a name, and a config warning from the repo's tether.json;
 * - `not-repo`: the folder is not a Git repository (the note alone).
 *
 * ta-coik.11: no setup confirmation goldens; the web shows the note (in `branch-off`) and sends.
 *
 * Every state is seeded synchronously through the real engine on an unconfined scope (the source is
 * placed on the drawn state, never asked for), the clock is driven by hand, and the client throws on
 * any send (a golden that sends anything fails).
 */
enum class WorktreeShot(val id: String) {
    BranchOff("draft-worktree-branch-off"),
    ExistingBranch("draft-worktree-existing-branch"),
    PullRequest("draft-worktree-pr"),
    NotRepo("draft-worktree-not-repo"),
}

private val exactCompare = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f))

private const val WORKTREE_PROMPT = "Review the sidebar change and run the tests."

private fun noSend(what: String): Nothing = throw AssertionError("a worktree golden must not $what")

private val failingWorktreeActions = DraftSheetActions(
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
    onSelectIsolation = { noSend("pick an isolation mode") },
    onWorktreeField = { _, _ -> noSend("type a worktree field") },
)

private class WorktreeSeed(shot: WorktreeShot) {
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

    private fun repo(hasSetup: Boolean, scripts: Int = 0, warnings: List<String> = emptyList()) = WorktreeSourceInfo(
        cwd = DraftFixtures.ROOT,
        isRepo = true,
        remote = "origin",
        defaultBaseRef = "origin/main",
        branches = listOf("origin/main", "origin/feat/sidebar", "main", "feat/sidebar"),
        configPresent = hasSetup || scripts > 0 || warnings.isNotEmpty(),
        configWarnings = warnings,
        hasSetup = hasSetup,
        declaredScripts = List(scripts) { WorktreeDeclaredScript("s$it", "script", null) },
    )

    init {
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        model.selectProviderAndModel("work", "m1")
        model.setText(WORKTREE_PROMPT)
        val source: WorktreeSourceInfo = when (shot) {
            WorktreeShot.BranchOff -> {
                check(model.selectIsolation("branch-off"))
                repo(hasSetup = true, scripts = 2)
            }
            WorktreeShot.ExistingBranch -> {
                check(model.selectIsolation("checkout-branch"))
                check(model.setWorktreeField(WorktreeField.Branch, "feat/sidebar"))
                repo(hasSetup = false)
            }
            WorktreeShot.PullRequest -> {
                check(model.selectIsolation("checkout-pr"))
                check(model.setWorktreeField(WorktreeField.Pr, "42"))
                check(model.setWorktreeField(WorktreeField.Slug, "review"))
                repo(hasSetup = false, scripts = 1, warnings = listOf("scripts.web: \"port\" must be a number between 1 and 65535; ignored."))
            }
            WorktreeShot.NotRepo -> {
                check(model.selectIsolation("branch-off"))
                WorktreeSourceInfo(cwd = DraftFixtures.ROOT, isRepo = false)
            }
        }
        readiness = model.readiness()
        state = model.state.value.copy(worktreeSource = source)
    }

    fun inputs() = DraftSheetInputs(
        draft = state,
        browser = draftBrowserInputs(state, client.providerCatalog.value, DraftFixtures.providers, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 1_700_000_000_000L),
        quickPicks = workspaceQuickPicks(listOf("/srv/ws/parity-app"), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
        workspaceRoot = DraftFixtures.ROOT,
        readiness = readiness,
        options = DraftSessionOptionsModel.of(state),
    )

    fun close() = job.cancel()
}

fun ComposeContentTestRule.snapWorktree(shot: WorktreeShot, skin: TetherSkin, phone: Boolean, name: String) {
    mainClock.autoAdvance = false
    val seed = WorktreeSeed(shot)
    val layout = if (phone) TetherLayoutClass.Phone else TetherLayoutClass.Expanded
    setContent {
        Box(Modifier.fillMaxSize()) {
            val slots = if (phone) placeholderSlots() else expandedSlots()
            val stage = EmptyStage.Welcome(connected = true, providers = ShellFixtures.providers)
            if (phone) ShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots) else ExpandedShellUnderTest(skin, PhoneShellState(), null, emptyStage = stage, slots = slots)
            TetherTheme(choiceFor(skin)) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DraftComposerFrame(seed.inputs(), failingWorktreeActions, layout = layout)
                }
            }
        }
    }
    mainClock.advanceTimeBy(700)
    waitForIdle()
    val path = "src/test/screenshots/$name/${skin.id}-${if (phone) "phone" else "tablet"}.png"
    onRoot().captureRoboImage(path, roborazziOptions = exactCompare)
    seed.close()
}

/** Every worktree state × both Studio skins at the web's phone viewport (412×915 @2.625). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class WorktreePhoneScreenshotTest(private val shot: WorktreeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapWorktree(shot, skin, phone = true, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = WorktreeShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** Every worktree state × both Studio skins at the web's tablet viewport (1280×800 @1x). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class WorktreeExpandedScreenshotTest(private val shot: WorktreeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapWorktree(shot, skin, phone = false, shot.id)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = WorktreeShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale on a phone: the new-branch row with its notes. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class WorktreeFontScaleScreenshotTest(private val shot: WorktreeShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun sheet() = rule.snapWorktree(shot, skin, phone = true, "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(WorktreeShot.BranchOff).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
