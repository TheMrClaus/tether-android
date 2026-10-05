package com.tether.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.tether.app.client.ClaudeAccountProfile
import com.tether.app.client.ClaudeLoginPoll
import com.tether.app.client.ClaudeLoginStarted
import com.tether.app.client.GitHubStatus
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupClaudeStatus
import com.tether.app.client.SetupGitHubPoll
import com.tether.app.client.SetupFinish
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T10.6: every step of the setup wizard, phone (412x915 @2.625) and tablet (1280x800 @1), light and dark.
 * `setup-welcome` the page's two-column hero (stacked on a phone) with the detected tools; `-operator` /
 * `-operator-mismatch` the account step and its warning; `-harnesses` the detected list (and `-end`, its
 * foot), `-harnesses-locate` an undetected one ticked (browse / paste a path), `-harnesses-bundled` the
 * bundled warning and isolation choice; `-workspace` the mounted-folder picks; `-github` / `-claude` the
 * two skippable stations: `-github` its choices, `-github-connected` a host already logged in, `-github-device`
 * the one-time code and its keys, `-github-token` the paste field with the server's refusal; `-claude` the
 * accounts list, `-claude-login` an account's sign-in prompt, `-claude-unavailable` a host with no Claude harness; `-review` the plate and its error line; `-done-manual` /
 * `-done-automatic` the restart step. Timing-free: the server is a scripted fake (nothing on a network), the
 * clock is held, focus is cleared (no caret) and the password is a fixed example.
 */
enum class SetupShot(val id: String, val scrollTo: String? = null) {
    Welcome("setup-welcome"),
    Operator("setup-operator"),
    OperatorMismatch("setup-operator-mismatch"),
    Harnesses("setup-harnesses"),
    HarnessesEnd("setup-harnesses-end", SetupTags.Bundled),
    HarnessesLocate("setup-harnesses-locate", SetupTags.locator("opencode")),
    HarnessesBundled("setup-harnesses-bundled", SetupTags.IsolationGuided),
    Workspace("setup-workspace"),
    GitHub("setup-github"),
    GitHubConnected("setup-github-connected"),
    GitHubDevice("setup-github-device", SetupTags.GitHubCancel),
    GitHubToken("setup-github-token", SetupTags.GitHubTokenSave),
    Claude("setup-claude"),
    ClaudeLogin("setup-claude-login", SetupTags.ClaudeCancel),
    ClaudeUnavailable("setup-claude-unavailable"),
    Review("setup-review"),
    ReviewError("setup-review-error", SetupTags.Apply),
    DoneManual("setup-done-manual"),
    DoneAutomatic("setup-done-automatic"),
}

private fun ComposeContentTestRule.snapSetup(shot: SetupShot, skin: TetherSkin, size: String, scope: CoroutineScope) {
    val api = FakeSetupApi(
        state = SetupCall.Ok(
            FakeSetupApi.sampleState(
                runtime = if (shot == SetupShot.DoneAutomatic) "container" else "native",
                candidates = if (shot == SetupShot.Workspace) listOf("/srv/projects", "/srv/work") else emptyList(),
            ),
        ),
    )
    api.complete = {
        when (shot) {
            SetupShot.ReviewError -> SetupCall.Failed(400, "A password is required.")
            SetupShot.DoneAutomatic -> SetupCall.Ok(SetupFinish("automatic", "container"))
            SetupShot.DoneManual -> SetupCall.Ok(SetupFinish("manual", "systemd"))
            else -> SetupCall.Ok(SetupFinish("manual", "native"))
        }
    }
    // The stations' polls wait out the capture (a first look at once, then an hour): the state is the scripted one.
    val model = SetupWizardModel(api, scope, restartPollMs = 3_600_000, accountTiming = SetupAccountTiming(initialMs = 0, pollMs = 3_600_000, retryMs = 3_600_000))
    model.load()
    // Welcome is step 0; each shot is the step it names, reached the way the operator reaches it.
    val to = when (shot) {
        SetupShot.Welcome -> 0
        SetupShot.Operator, SetupShot.OperatorMismatch -> 1
        SetupShot.Harnesses, SetupShot.HarnessesEnd, SetupShot.HarnessesLocate, SetupShot.HarnessesBundled -> 2
        SetupShot.Workspace -> 3
        SetupShot.GitHub, SetupShot.GitHubConnected, SetupShot.GitHubDevice, SetupShot.GitHubToken -> 4
        SetupShot.Claude, SetupShot.ClaudeLogin, SetupShot.ClaudeUnavailable -> 5
        else -> 6
    }
    if (to > 0) model.begin()
    if (shot != SetupShot.Operator) {
        model.username = "operator"
        model.password = "correct horse"
        model.confirmPassword = if (shot == SetupShot.OperatorMismatch) "correct hose" else "correct horse"
    }
    if (shot == SetupShot.HarnessesLocate) model.toggleEngine("opencode")
    if (shot == SetupShot.HarnessesBundled || shot == SetupShot.Review) {
        model.useBundled = shot == SetupShot.HarnessesBundled
        model.isolation = Isolation.Guided
    }
    if (shot == SetupShot.Workspace) model.pickWorkspace("/srv/projects")
    if (shot == SetupShot.ClaudeUnavailable) model.toggleEngine("claude")
    repeat(to - 1) { model.next() }
    model.stage(shot, api)
    if (shot == SetupShot.DoneManual || shot == SetupShot.DoneAutomatic) model.applyBlocking()
    if (shot == SetupShot.ReviewError) model.applyBlocking()
    var focus: FocusManager? = null
    mainClock.autoAdvance = false
    setContent {
        focus = LocalFocusManager.current
        TetherTheme(skin.mode) {
            CompositionLocalProvider(LocalReducedMotion provides true) { SetupWizardScreen(model) }
        }
    }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    runOnIdle { focus?.clearFocus(force = true) }
    mainClock.advanceTimeBy(600)
    waitForIdle()
    if (shot.scrollTo != null) {
        // The scroll animates: let the clock run for it, then hold it again for the capture.
        mainClock.autoAdvance = true
        onNodeWithTag(shot.scrollTo, useUnmergedTree = true).performScrollTo()
        waitForIdle()
        mainClock.autoAdvance = false
    }
    onRoot().captureRoboImage(
        "src/test/screenshots/${shot.id}/${skin.id}-$size.png",
        roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)),
    )
}

/** The GitHub / Claude accounts station of [shot] in the state it names (the fake answers at once; nothing waits). */
private fun SetupWizardModel.stage(shot: SetupShot, api: FakeSetupApi) {
    when (shot) {
        SetupShot.GitHubConnected -> {
            api.githubStatus = SetupCall.Ok(GitHubStatus(true, "2.60.0", true, "octo-operator", listOf("repo", "read:org", "gist"), false))
            github.enter()
        }
        SetupShot.GitHubDevice -> {
            api.githubPolls += SetupCall.Ok(SetupGitHubPoll(true, "pending", "ABCD-1234", "https://github.com/login/device", null))
            github.enter()
            kotlinx.coroutines.runBlocking { github.startDeviceNow() }
        }
        SetupShot.GitHubToken -> {
            api.githubToken = SetupCall.Failed(400, "Bad credentials")
            github.enter()
            github.chooseToken()
            github.typeToken("ghp_exampleexampleexample")
            kotlinx.coroutines.runBlocking { github.saveTokenNow() }
        }
        SetupShot.GitHub -> github.enter()
        SetupShot.Claude -> {
            api.claudeList = SetupCall.Ok(listOf(ClaudeAccountProfile("default", "Default", imported = true), ClaudeAccountProfile("claude-work", "work", imported = false)))
            api.claudeStatuses = mapOf("default" to SetupClaudeStatus(true, "operator@example.com"))
            claude.enter()
        }
        SetupShot.ClaudeLogin -> {
            api.claudeList = SetupCall.Ok(listOf(ClaudeAccountProfile("default", "Default", imported = true), ClaudeAccountProfile("claude-work", "work", imported = false)))
            api.claudeStatuses = mapOf("default" to SetupClaudeStatus(true, "operator@example.com"))
            api.claudeStart = SetupCall.Ok(ClaudeLoginStarted("pending-url", null))
            api.claudePolls += SetupCall.Ok(ClaudeLoginPoll(true, "awaiting-code", "https://claude.ai/oauth/authorize?code=true&client_id=example", null))
            claude.enter()
            kotlinx.coroutines.runBlocking { claude.startLoginNow("claude-work") }
        }
        SetupShot.ClaudeUnavailable -> claude.enter()
        else -> Unit
    }
}

/** The Review step's Apply, run to its end on the caller's thread (the scripted fake answers at once). */
private fun SetupWizardModel.applyBlocking() {
    kotlinx.coroutines.runBlocking { applyNow() }
}

abstract class SetupShotBase {
    @get:Rule val rule = createComposeRule()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SetupPhoneScreenshotTest(private val shot: SetupShot, private val skin: TetherSkin) : SetupShotBase() {
    @Test fun setup() = rule.snapSetup(shot, skin, "phone", scope)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SetupShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class SetupTabletScreenshotTest(private val shot: SetupShot, private val skin: TetherSkin) : SetupShotBase() {
    @Test fun setup() = rule.snapSetup(shot, skin, "tablet", scope)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SetupShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
