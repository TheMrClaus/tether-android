package com.tether.app.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import com.tether.app.client.HttpSetupApi
import com.tether.app.ui.FolderPickerTags
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherTheme
import com.tether.app.ui.theme.ThemeMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * T10.6: the wizard end to end on the JVM, the real screen over the real [HttpSetupApi] against a fake
 * first-run server shaped from lib/setup-server.mjs ([SetupServer]); no real server. The golden path
 * (Welcome through Configured), the gates, the errors the page shows, the restart step and the 401.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SetupWizardBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private val fake = SetupServer()
    private val web = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signIns = AtomicInteger()
    private lateinit var model: SetupWizardModel

    @After fun tearDown() {
        scope.cancel()
        web.shutdown()
    }

    private fun launch(pollMs: Long = 50) {
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        model = SetupWizardModel(HttpSetupApi(http, web.url("/")), scope, restartPollMs = pollMs)
        model.onSignIn = { signIns.incrementAndGet() }
        rule.setContent {
            TetherTheme(ThemeMode.Light) {
                CompositionLocalProvider(LocalReducedMotion provides true) { SetupWizardScreen(model) }
            }
        }
        model.load()
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = rule.waitUntil(timeout) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun tag(t: String): SemanticsNodeInteraction = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun exists(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(t: String) {
        tag(t).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun editable(t: String) = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(t)), useUnmergedTree = true)

    private fun type(t: String, text: String) {
        editable(t).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        rule.waitForIdle()
    }

    private fun begin() {
        waitFor { model.state != null }
        tap(SetupTags.Begin)
    }

    private fun fillOperator(password: String = "correct horse") {
        type(SetupTags.Username, "operator")
        type(SetupTags.Password, password)
        type(SetupTags.Confirm, password)
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test fun theGoldenPathFromWelcomeToConfiguredAndOnToSignIn() {
        launch()
        // Welcome waits for the state; the Welcome inventory lists what was detected.
        begin()
        waitFor { shows("Name the operator.") }
        tag(SetupTags.Continue).assertIsNotEnabled()
        fillOperator()
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)

        waitFor { shows("Choose your harnesses.") }
        // Detection ticked what it found (claude, codex); opencode is not found and not ticked.
        assertTrue(model.engines.containsAll(listOf("claude", "codex")))
        assertTrue("opencode" !in model.engines)
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)

        waitFor { shows("Point at your projects.") }
        assertEquals("/home/op", model.workspaceRoot)
        tap(SetupTags.Continue)
        // GitHub and Claude accounts: the web lets both be skipped.
        waitFor { exists(SetupTags.GitHubSeam) }
        tag(SetupTags.Continue).assertIsEnabled()
        tap(SetupTags.Continue)
        waitFor { exists(SetupTags.ClaudeSeam) }
        tap(SetupTags.Continue)

        waitFor { shows("Review and switch on.") }
        assertTrue(shows("you will restart Tether by hand"))
        assertTrue(exists(SetupTags.Plate))
        // Nothing has been written yet.
        assertTrue(fake.calls("/api/setup/complete").isEmpty())
        tap(SetupTags.Apply)

        waitFor { shows("Configured.") }
        assertTrue(shows("Restart Tether to boot your console, then sign in."))
        assertTrue(shows("npm start"))
        val complete = fake.calls("/api/setup/complete").single()
        assertEquals("POST", complete.method)
        assertTrue(complete.contentType!!.startsWith("application/json"))
        assertNull(complete.origin)
        assertNull(complete.cookie)
        val settings = com.tether.app.protocol.TetherJson.parseToJsonElement(complete.body).let { (it as JsonObject)["settings"] as JsonObject }
        assertEquals("operator", settings.str("username"))
        assertEquals("correct horse", settings.str("password"))
        assertEquals("claude,codex", settings.str("headlessModes"))
        assertEquals("/home/op", settings.str("workspaceRoot"))
        assertEquals("false", settings.str("useBundledHarnesses"))
        assertEquals("true", settings.str("shareHostConfig"))
        assertEquals("false", settings.str("guidedIsolation"))

        assertEquals(0, signIns.get())
        tap(SetupTags.SignIn)
        assertEquals(1, signIns.get())
    }

    @Test fun everyWriteTheWizardMakesIsJsonWithNoOrigin() {
        launch()
        begin()
        fillOperator()
        model.next(); model.next(); model.next(); model.next(); model.next()
        rule.waitForIdle()
        tap(SetupTags.Apply)
        waitFor { shows("Configured.") }
        val writes = fake.seen.filter { it.method != "GET" }
        assertTrue(writes.isNotEmpty())
        writes.forEach {
            assertTrue("${it.method} ${it.path} was not JSON: ${it.contentType}", it.contentType!!.startsWith("application/json"))
            assertNull(it.origin)
        }
        assertTrue(fake.seen.all { it.cookie == null && it.authorization == null })
    }

    @Test fun mismatchedPasswordsWarnAndHoldTheStep() {
        launch()
        begin()
        waitFor { shows("Name the operator.") }
        type(SetupTags.Username, "operator")
        type(SetupTags.Password, "one")
        type(SetupTags.Confirm, "two")
        assertTrue(shows("Passwords do not match yet."))
        tag(SetupTags.Continue).assertIsNotEnabled()
        type(SetupTags.Confirm, "one")
        assertTrue(!shows("Passwords do not match yet."))
        tag(SetupTags.Continue).assertIsEnabled()
    }

    @Test fun anUndetectedHarnessIsLocatedByPathAndCheckedByTheServer() {
        launch()
        begin()
        fillOperator()
        tap(SetupTags.Continue)
        waitFor { shows("Choose your harnesses.") && model.detected.isNotEmpty() }
        tap(SetupTags.engine("opencode"))
        assertTrue(shows("NOT FOUND"))
        tag(SetupTags.Continue).assertIsNotEnabled()
        type(SetupTags.locator("opencode"), "/opt/opencode")
        waitFor { shows("Found here.") }
        val check = fake.calls("/api/setup/validate").single()
        assertTrue(check.contentType!!.startsWith("application/json"))
        assertEquals("""{"kind":"engineBinary","engine":"opencode","value":"/opt/opencode"}""", check.body)
        tag(SetupTags.Continue).assertIsEnabled()
        assertTrue(shows("MANUAL"))
    }

    @Test fun theWorkspaceIsChosenInTheSharedFolderPickerOverTheSetupBrowse() {
        launch()
        begin()
        model.next(); model.next()
        waitFor { shows("Point at your projects.") }
        tap(SetupTags.Browse)
        waitFor { exists(FolderPickerTags.Dialog) && shows("projects") }
        assertEquals("/api/setup/browse?path=%2Fhome%2Fop", fake.calls("/api/setup/browse").first().target)
        // Navigate into a folder, then use it.
        rule.onAllNodesWithText("projects", substring = false).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitFor { fake.calls("/api/setup/browse").size == 2 }
        waitFor { shows("/home/op/projects") }
        tap(FolderPickerTags.Use)
        waitFor { !exists(FolderPickerTags.Dialog) }
        assertEquals("/home/op/projects", model.workspaceRoot)
        // The setup picker has no "Create a new folder": the server has no such route (page.tsx FolderBrowser).
        assertTrue(fake.seen.none { it.path == "/api/setup/mkdir" })
    }

    @Test fun anApplyRefusalIsShownInTheFooterAndTheWizardStaysOnReview() {
        fake.complete = { SetupServer.json(400, """{"ok":false,"problems":[{"key":"password","message":"A password is required."}]}""") }
        launch()
        begin()
        model.next(); model.next(); model.next(); model.next(); model.next()
        waitFor { shows("Review and switch on.") }
        tap(SetupTags.Apply)
        waitFor { exists(SetupTags.Error) }
        assertTrue(shows("A password is required."))
        assertTrue(shows("Review and switch on."))
        tag(SetupTags.Apply).assertIsEnabled()
    }

    @Test fun aSupervisedRestartWaitsForTheServerThenGoesToSignIn() {
        fake.state = SetupServer.DEFAULT_STATE.replace("\"runtime\":\"native\"", "\"runtime\":\"container\"")
        fake.complete = { SetupServer.json(200, """{"ok":true,"runtime":"container","restart":"automatic"}""") }
        fake.healthzBouncing = 2
        launch(pollMs = 30)
        begin()
        model.next(); model.next(); model.next(); model.next(); model.next()
        waitFor { shows("you will restart").not() && shows("the server restarts itself") }
        tap(SetupTags.Apply)
        waitFor { shows("Configured.") }
        assertTrue(shows("Tether is restarting into your console."))
        assertTrue(exists(SetupTags.Waiting))
        waitFor { signIns.get() == 1 }
        // Two looks saw the server still in setup mode, the third saw it configured.
        assertTrue(fake.calls("/healthz").size >= 3)
    }

    @Test fun aSystemdInstallShowsItsOwnRestartCommand() {
        fake.state = SetupServer.DEFAULT_STATE.replace("\"runtime\":\"native\"", "\"runtime\":\"systemd\"")
        fake.complete = { SetupServer.json(200, """{"ok":true,"runtime":"systemd","restart":"manual"}""") }
        launch()
        begin()
        model.next(); model.next(); model.next(); model.next(); model.next()
        waitFor { shows("Review and switch on.") }
        tap(SetupTags.Apply)
        waitFor { shows("systemctl --user restart tether.service") }
        assertTrue(!shows("npm start"))
    }

    @Test fun aSetupThatIsAlreadyDoneGoesStraightToSignIn() {
        fake.configured = true
        launch()
        waitFor { signIns.get() == 1 }
        assertTrue(!shows("The setup service did not answer."))
    }

    @Test fun aServerThatDoesNotAnswerOffersTryAgain() {
        fake.state = "<html>nope</html>"
        launch()
        waitFor { exists(SetupTags.Retry) }
        assertTrue(shows("The setup service did not answer."))
        tag(SetupTags.Begin).assertIsNotEnabled()
        fake.state = SetupServer.DEFAULT_STATE
        tap(SetupTags.Retry)
        waitFor { !exists(SetupTags.Retry) }
        begin()
        waitFor { shows("Name the operator.") }
    }

    @Test fun anEnvForcedPasswordIsShownReadOnly() {
        fake.state = SetupServer.DEFAULT_STATE.replace("\"password\":false", "\"password\":true")
        launch()
        begin()
        waitFor { shows("Name the operator.") }
        assertTrue(shows("The password is set by the environment (TETHER_PASSWORD) and cannot be changed here."))
        assertTrue(!exists(SetupTags.Password))
        type(SetupTags.Username, "operator")
        tag(SetupTags.Continue).assertIsEnabled()
    }
}
