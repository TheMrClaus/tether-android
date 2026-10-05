package com.tether.app.ui.setup

import com.tether.app.client.BinaryCheck
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupFinish
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10.6: page.tsx's rules (tether 90fbb9f app/setup/page.tsx), without a screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupModelTest {
    private fun TestScope.model(api: FakeSetupApi, poll: Long = 1_500): Pair<SetupWizardModel, IntArray> {
        val signIns = IntArray(1)
        val model = SetupWizardModel(api, this, restartPollMs = poll)
        model.onSignIn = { signIns[0]++ }
        return model to signIns
    }

    private suspend fun TestScope.loaded(api: FakeSetupApi = FakeSetupApi()): Pair<SetupWizardModel, IntArray> {
        val pair = model(api)
        pair.first.load()
        advanceUntilIdle()
        return pair
    }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.content

    @Test fun loadingPreselectsEveryDetectedHarnessAndSeedsTheWorkspace() = runTest {
        val (m, _) = loaded()
        assertEquals(listOf("claude", "codex", "reasonix"), m.engines)
        assertEquals("/home/op", m.workspaceRoot)
        assertFalse(m.detecting)
        assertEquals("", m.stateError)
    }

    @Test fun savedSettingsSeedTheFormAndDetectionAddsToThem() = runTest {
        val api = FakeSetupApi(state = SetupCall.Ok(FakeSetupApi.sampleState(settings = mapOf("headlessModes" to "pi, bogus", "workspaceRoot" to "/srv/code", "username" to "ada"))))
        val (m, _) = loaded(api)
        assertEquals("ada", m.username)
        assertEquals("/srv/code", m.workspaceRoot)
        // "bogus" is not a supported mode; detection merges its finds in after the seed.
        assertEquals(listOf("pi", "claude", "codex", "reasonix"), m.engines)
    }

    @Test fun detectionNeverFightsAToggle() = runTest {
        val (m, _) = loaded()
        m.toggleEngine("claude")
        m.runDetect()
        advanceUntilIdle()
        assertEquals(listOf("codex", "reasonix"), m.engines)
    }

    @Test fun aFailedDetectionIsQuiet() = runTest {
        val api = FakeSetupApi(detect = SetupCall.Failed(null, "boom"))
        val (m, _) = loaded(api)
        assertTrue(m.detected.isEmpty())
        assertEquals("", m.stateError)
        assertFalse(m.detecting)
    }

    @Test fun aFailedStateIsARetryableErrorAnd401IsSignIn() = runTest {
        val api = FakeSetupApi(state = SetupCall.Failed(500, "The setup service did not answer."))
        val (m, signIns) = loaded(api)
        assertEquals("The setup service did not answer.", m.stateError)
        assertEquals(0, signIns[0])
        m.begin()
        assertEquals(0, m.step) // Begin waits for the state.
        api.state = SetupCall.Ok(FakeSetupApi.sampleState())
        m.retryLoad()
        advanceUntilIdle()
        assertEquals("", m.stateError)
        m.begin()
        assertEquals(1, m.step)

        val done = FakeSetupApi(state = SetupCall.Failed(401, "Authentication required."))
        val (m2, signIns2) = loaded(done)
        assertEquals(1, signIns2[0])
        assertEquals("", m2.stateError)
    }

    @Test fun theOperatorStepNeedsAUsernameAndMatchingPasswords() = runTest {
        val (m, _) = loaded()
        m.begin()
        assertFalse(m.canAdvance)
        m.username = "  op  "
        assertFalse(m.canAdvance)
        m.password = "pw"
        m.confirmPassword = "px"
        assertFalse(m.canAdvance)
        m.confirmPassword = "pw"
        assertTrue(m.canAdvance)
        m.username = "   "
        assertFalse(m.canAdvance)
    }

    @Test fun anEnvForcedPasswordNeedsNoPasswordButStillAUsername() = runTest {
        val (m, _) = loaded(FakeSetupApi(state = SetupCall.Ok(FakeSetupApi.sampleState(forced = mapOf("password" to true)))))
        m.begin()
        assertFalse(m.canAdvance)
        m.username = "op"
        assertTrue(m.canAdvance)
    }

    @Test fun harnessesNeedAFoundInstallOrALocationOrBundled() = runTest {
        val api = FakeSetupApi()
        val (m, _) = loaded(api)
        m.begin(); m.next()
        assertEquals(2, m.step)
        assertTrue(m.enginesValid) // claude, codex, reasonix: all found
        m.toggleEngine("opencode")
        assertFalse(m.enginesValid) // opencode: not found, not located
        m.setLocator("opencode", "/opt/oc")
        advanceUntilIdle()
        assertTrue(m.enginesValid)
        assertEquals("opencode" to "/opt/oc", api.validated[0])
        m.setLocator("opencode", "")
        assertFalse(m.enginesValid)
        m.useBundled = true
        assertTrue(m.enginesValid)
        m.toggleEngine("claude"); m.toggleEngine("codex"); m.toggleEngine("reasonix"); m.toggleEngine("opencode")
        assertFalse(m.enginesValid) // none ticked
    }

    @Test fun aLocatorCheckShowsTheServersMessageAndAStaleOneIsDropped() = runTest {
        val api = FakeSetupApi()
        val (m, _) = loaded(api)
        api.validate = { _, value -> SetupCall.Ok(BinaryCheck(value == "/good", if (value == "/good") null else "No opencode executable in that folder.")) }
        m.setLocator("opencode", " /bad ")
        advanceUntilIdle()
        assertEquals(false, m.locatorChecks["opencode"]?.ok)
        assertEquals("No opencode executable in that folder.", m.locatorChecks["opencode"]?.message)
        assertEquals("/bad", api.validated.single().second) // trimmed, as the page sends it
        // A failed check is quiet: no line.
        api.validate = { _, _ -> SetupCall.Failed(null, "down") }
        m.setLocator("opencode", "/x")
        advanceUntilIdle()
        assertNull(m.locatorChecks["opencode"])
    }

    @Test fun theWorkspaceNeedsAFolderAndGitHubAndClaudeAreSkippable() = runTest {
        val (m, _) = loaded()
        m.begin(); m.next(); m.next()
        assertEquals(SetupStep.Workspace.ordinal, m.step)
        assertTrue(m.canAdvance)
        m.pickWorkspace("")
        assertFalse(m.canAdvance)
        m.pickWorkspace("/srv/code")
        m.next()
        assertEquals(SetupStep.GitHub.ordinal, m.step)
        assertTrue(m.canAdvance)
        m.next()
        assertEquals(SetupStep.ClaudeAccounts.ordinal, m.step)
        assertTrue(m.canAdvance)
        m.next()
        assertEquals(SetupStep.Review.ordinal, m.step)
        m.back()
        assertEquals(SetupStep.ClaudeAccounts.ordinal, m.step)
    }

    @Test fun thePickerListsBrowsesAndChoosesForItsTarget() = runTest {
        val api = FakeSetupApi()
        val (m, _) = loaded(api)
        m.openPicker(PickerTarget.Workspace, "/home/op")
        advanceUntilIdle()
        assertEquals("/home/op", m.pickerListing?.current)
        m.browse("/home/op/projects")
        advanceUntilIdle()
        assertEquals("/home/op/projects", m.pickerListing?.current)
        m.choose("/home/op/projects")
        assertEquals("/home/op/projects", m.workspaceRoot)
        assertNull(m.picker)

        m.openPicker(PickerTarget.Locate("pi"), "/home/op")
        advanceUntilIdle()
        m.choose("/opt/pi")
        advanceUntilIdle()
        assertEquals("/opt/pi", m.locators["pi"])
        assertEquals("pi" to "/opt/pi", api.validated.single())
    }

    @Test fun aFailedBrowseShowsTheServersSentenceAndALateOneIsDropped() = runTest {
        val api = FakeSetupApi()
        api.browse = { SetupCall.Failed(400, "ENOENT: no such directory") }
        val (m, _) = loaded(api)
        m.openPicker(PickerTarget.Workspace, "/nope")
        advanceUntilIdle()
        assertEquals("ENOENT: no such directory", m.pickerError)
        assertNull(m.pickerListing)
        m.closePicker()
        m.browse("/x")
        advanceUntilIdle()
        assertNull(m.pickerListing) // closed: a reply lands nowhere
    }

    @Test fun applyOwnHostCliSendsExactlyWhatThePageSends() = runTest {
        val api = FakeSetupApi()
        val (m, _) = loaded(api)
        m.username = " op "
        m.password = "pw"
        m.confirmPassword = "pw"
        m.toggleEngine("opencode")
        m.setLocator("opencode", " /opt/oc ")
        advanceUntilIdle()
        m.pickWorkspace("/srv/code")
        m.apply()
        advanceUntilIdle()
        val body = api.completed.single()
        assertEquals("op", body.str("username"))
        assertEquals("pw", body.str("password"))
        assertEquals("claude,codex,reasonix,opencode", body.str("headlessModes"))
        assertEquals("/srv/code", body.str("workspaceRoot"))
        assertEquals("false", body.str("useBundledHarnesses"))
        assertEquals("true", body.str("shareHostConfig"))
        assertEquals("false", body.str("guidedIsolation"))
        assertEquals("/opt/oc", body.str("opencodeCommand"))
        assertNull(body["claudeCliPath"]) // detected, not located
        assertEquals(SetupFinish("manual", "native"), m.finish)
        assertFalse(m.busy)
    }

    @Test fun applyBundledGuidedSendsTheBundledFlagsAndNoLocators() = runTest {
        val api = FakeSetupApi()
        val (m, _) = loaded(api)
        m.username = "op"; m.password = "pw"; m.confirmPassword = "pw"
        m.toggleEngine("opencode")
        m.setLocator("opencode", "/opt/oc")
        advanceUntilIdle()
        m.useBundled = true
        m.isolation = Isolation.Guided
        m.apply()
        advanceUntilIdle()
        val body = api.completed.single()
        assertEquals("true", body.str("useBundledHarnesses"))
        assertEquals("false", body.str("shareHostConfig"))
        assertEquals("true", body.str("guidedIsolation"))
        assertNull(body["opencodeCommand"])
    }

    @Test fun applyLeavesOutWhatTheEnvironmentForces() = runTest {
        val forced = mapOf("password" to true, "headlessModes" to true, "workspaceRoot" to true, "opencodeCommand" to true)
        val api = FakeSetupApi(state = SetupCall.Ok(FakeSetupApi.sampleState(forced = forced)))
        val (m, _) = loaded(api)
        m.username = "op"
        m.password = "typed anyway"
        m.toggleEngine("opencode")
        m.setLocator("opencode", "/opt/oc")
        advanceUntilIdle()
        m.apply()
        advanceUntilIdle()
        val body = api.completed.single()
        assertNull(body["password"])
        assertNull(body["headlessModes"])
        assertNull(body["workspaceRoot"])
        assertNull(body["opencodeCommand"])
        assertEquals("op", body.str("username"))
    }

    @Test fun anApplyFailureStaysOnReviewWithTheServersSentence() = runTest {
        val api = FakeSetupApi()
        api.complete = { SetupCall.Failed(400, "One. Two.") }
        val (m, _) = loaded(api)
        m.apply()
        advanceUntilIdle()
        assertEquals("One. Two.", m.error)
        assertNull(m.finish)
        assertFalse(m.busy)
        // Going back or on clears the line.
        m.back()
        assertEquals("", m.error)
    }

    @Test fun anAutomaticRestartPollsHealthzThenGoesToSignIn() = runTest {
        val api = FakeSetupApi()
        api.complete = { SetupCall.Ok(SetupFinish("automatic", "container")) }
        api.configuredAnswers = ArrayDeque(listOf(false, false, true))
        val (m, signIns) = loaded(api)
        m.apply()
        advanceUntilIdle()
        assertTrue(m.finish!!.automatic)
        val poll = launchPoll(m)
        advanceUntilIdle()
        assertEquals(1, signIns[0])
        assertTrue(api.configuredAnswers.isEmpty())
        poll.cancel()
        // Three looks, 1.5 s apart: the first only after the first interval.
        assertEquals(4_500L, testScheduler.currentTime)
    }

    private fun TestScope.launchPoll(m: SetupWizardModel) = launch { m.pollRestart() }

    @Test fun aManualRestartDoesNotPoll() = runTest {
        val api = FakeSetupApi()
        api.configuredAnswers = ArrayDeque(listOf(true))
        val (m, signIns) = loaded(api)
        m.apply()
        advanceUntilIdle()
        m.pollRestart()
        assertEquals(0, signIns[0])
        m.goToSignIn()
        assertEquals(1, signIns[0])
    }

    @Test fun resetForgetsThePasswordAndEverythingTyped() = runTest {
        val (m, _) = loaded()
        m.begin()
        m.username = "op"; m.password = "pw"; m.confirmPassword = "pw"; m.showPassword = true
        m.reset()
        assertEquals("", m.password)
        assertEquals("", m.confirmPassword)
        assertEquals("", m.username)
        assertEquals(0, m.step)
        assertNull(m.state)
        assertTrue(m.engines.isEmpty())
        assertFalse(m.showPassword)
    }
}
