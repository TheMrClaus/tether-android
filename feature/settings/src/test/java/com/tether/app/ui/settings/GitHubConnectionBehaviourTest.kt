package com.tether.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.FilesAuthority
import com.tether.app.client.GitHubConnectionSource
import com.tether.app.client.HttpGitHubConnection
import com.tether.app.client.SecurityResult
import com.tether.app.client.serverOrigin
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * ta-coik.21: Settings → Advanced → GitHub connection through the semantics tree, against
 * settings-dialog.tsx 90fbb9f :1082-1166: the rows of each state in the web's order and words; the
 * token field as the web's password input (masked, typed into, no copy or cut, no reveal; the token in
 * no semantics, log, preference or saved state); Verify & save over the real source to a fake server
 * (the trimmed token in the one body); the device flow's code, its open link and Cancel; Remove Tether
 * token sent at once (no app-only confirmation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GitHubConnectionBehaviourTest {
    private val tmp = TemporaryFolder()
    private val store = PrefsStore(tmp)
    private val compose = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(store).around(compose)

    private val state = SettingsDialogState(SettingsTab.Advanced)
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
    private val fast = GitHubPollPace(first = 20, next = 20, afterFailure = 20)
    private lateinit var c: GitHubConnectionController

    private fun show(source: GitHubConnectionSource, origin: String = ServerFixtures.ORIGIN, opener: LoginLinkOpener = LoginLinkOpener.None) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { GitHubConnectionController(source, origin, scope, pace = fast).also { c = it } }
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                SettingsUnderTest(store.prefs, state, serverSettings = ServerFixtures.binding(), github = GitHubBinding(controller, opener))
            }
        }
        compose.waitUntil(5_000) { state.draft != null }
        compose.waitUntil(5_000) { !c.loading }
        compose.waitForIdle()
    }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)

    private fun exists(t: String) = compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    /** The section's own texts, from its heading on (a key's label, said as its text and its name, once). */
    private fun sectionTexts(): List<String> {
        val all = texts()
        val from = all.indexOf(AdvancedRows.GITHUB)
        val to = all.indexOf(AdvancedRows.LIFECYCLE)
        return all.subList(from, to).fold(mutableListOf()) { out, s -> if (out.lastOrNull() != s) out += s; out }
    }

    private fun allSemantics(): String {
        val out = StringBuilder()
        fun walk(node: SemanticsNode) {
            for ((key, value) in node.config) out.append(key.name).append('=').append(value).append('\n')
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    private fun savedState(): String = registry.performSave().toString() +
        with(SettingsDialogState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(state) }.toString()

    // ---- the rows of each state ----------------------------------------------------------------------

    @Test fun notLoggedInDrawsTheWebsRowsInOrder() {
        val gh = FakeGitHub()
        show(gh)
        assertEquals(
            listOf(
                AdvancedRows.GITHUB, AdvancedRows.GITHUB_CAPTION,
                GitHubCopy.STATUS, GitHubCopy.NOT_LOGGED_IN, GitHubCopy.RECHECK,
                GitHubCopy.CONNECT_TITLE, GitHubCopy.CONNECT_CAPTION, GitHubCopy.START,
                GitHubCopy.PAT_TITLE, "Stored server-side at state/github-token (0600). Requires repo, read:org, gist scopes.",
                GitHubCopy.TOKEN_TITLE, GitHubCopy.TOKEN_HINT, GitHubCopy.TOKEN_FIELD, GitHubCopy.TOKEN_PLACEHOLDER, GitHubCopy.VERIFY,
            ),
            sectionTexts(),
        )
        // :1158 disabled while the field is blank.
        tag(GitHubTags.Verify).assertIsNotEnabled()
        // Re-check reads the status again (:1110).
        tag(GitHubTags.Recheck).performScrollTo().performClick()
        compose.waitUntil(5_000) { gh.count("status@${ServerFixtures.ORIGIN}") == 2 }
    }

    @Test fun ghNotInstalledSaysSoAndStillOffersBothWays() {
        val gh = FakeGitHub()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.NOT_INSTALLED, ServerFixtures.ORIGIN, null)
        show(gh)
        val t = sectionTexts()
        assertTrue(t.contains(GitHubCopy.NOT_INSTALLED))
        assertTrue(t.contains(GitHubCopy.START))
        assertTrue(t.contains(GitHubCopy.VERIFY))
    }

    @Test fun aHostLoginShowsOnlyTheStatus() {
        val gh = FakeGitHub()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.HOST, ServerFixtures.ORIGIN, null)
        show(gh)
        assertEquals(
            listOf(AdvancedRows.GITHUB, AdvancedRows.GITHUB_CAPTION, GitHubCopy.STATUS, "Connected as @octocat · host gh login · gh 2.97.0 · gist, read:org, repo"),
            sectionTexts(),
        )
        assertFalse(exists(GitHubTags.Remove))
        assertFalse(exists(GitHubTags.Token))
    }

    @Test fun removeTetherTokenIsSentOnTheFirstTap() {
        val gh = FakeGitHub()
        gh.statuses += SecurityResult.Ok(GitHubFixtures.MANAGED, ServerFixtures.ORIGIN, null)
        show(gh)
        assertTrue(sectionTexts().contains(GitHubCopy.REMOVE_TOKEN))
        tag(GitHubTags.Remove).performScrollTo().performClick()
        // No confirmation (the web has none, :1106): sent, then the status is read again.
        compose.waitUntil(5_000) { gh.count("status@${ServerFixtures.ORIGIN}") == 2 }
        assertEquals(1, gh.count("logout@${ServerFixtures.ORIGIN}"))
        compose.waitUntil(5_000) { exists(GitHubTags.Token) }
    }

    @Test fun aFailedReadShowsItsReasonWithRetry() {
        val gh = FakeGitHub()
        gh.statuses += SecurityResult.Unavailable(500, ServerFixtures.ORIGIN)
        show(gh)
        assertTrue(sectionTexts().containsAll(listOf(GitHubCopy.STATUS, GitHubCopy.STATUS_FAILED, GitHubCopy.RETRY)))
        tag(GitHubTags.Retry).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.Recheck) }
    }

    // ---- the token: the web's password input ---------------------------------------------------------

    @Test fun theTokenFieldIsAPasswordInputWithNoCopyAndNoReveal() {
        ShadowLog.clear()
        show(FakeGitHub())
        val field = tag(GitHubTags.Token)
        field.performScrollTo().performTextReplacement(GitHubFixtures.FAKE_TOKEN)
        compose.waitForIdle()
        val config = field.fetchSemanticsNode().config
        assertTrue("masked", config.contains(SemanticsProperties.Password))
        // As the browser does for a password field: Copy and Cut do nothing (ta-coik.5's guard).
        assertEquals(NoCopyGuardCopy.ACTION_LABEL, config[SemanticsActions.CopyText].label)
        assertEquals(NoCopyGuardCopy.ACTION_LABEL, config[SemanticsActions.CutText].label)
        NoCopyProbe.seed()
        field.performSemanticsAction(SemanticsActions.SetSelection) { it(0, GitHubFixtures.FAKE_TOKEN.length, false) }
        field.performSemanticsAction(SemanticsActions.CopyText)
        field.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        assertEquals(NoCopyProbe.MARKER, NoCopyProbe.clip())
        assertEquals(GitHubFixtures.FAKE_TOKEN, c.tokenInput)
        // No reveal (the web's input has none), and the token is in no semantics, log, preference or saved state.
        assertFalse(sectionTexts().any { it.startsWith("Reveal") || it.startsWith("Hide") })
        // Every semantics property but `InputText` (Compose's untransformed copy for tests, which the
        // accessibility node never carries; its text is `EditableText`, masked).
        val exposed = allSemantics().lines().filterNot { it.startsWith("${SemanticsProperties.InputText.name}=") }
        assertFalse("semantics", exposed.any { it.contains(GitHubFixtures.FAKE_TOKEN) })
        assertFalse("the field's text is masked", config[SemanticsProperties.EditableText].text.contains("FAKE"))
        assertFalse("saved state", savedState().contains(GitHubFixtures.FAKE_TOKEN))
        assertFalse("logs", ShadowLog.getLogs().any { "${it.tag} ${it.msg} ${it.throwable}".contains(GitHubFixtures.FAKE_TOKEN) })
        assertFalse("prefs", store.stored().toString().contains(GitHubFixtures.FAKE_TOKEN))
        tag(GitHubTags.Verify).assertIsEnabled()
    }

    @Test fun verifyAndSaveSendsTheTrimmedTokenInItsOneBodyThenClearsTheField() {
        val server = MockWebServer()
        server.start()
        try {
            val json = "application/json; charset=utf-8"
            fun reply(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", json).setBody(body)
            server.enqueue(reply("""{"ghInstalled":false,"ghVersion":null,"authenticated":false,"account":null,"scopes":[],"managedToken":false}"""))
            server.enqueue(reply("""{"ok":true,"account":"octocat","scopes":["repo"]}"""))
            server.enqueue(reply("""{"ghInstalled":false,"ghVersion":null,"authenticated":false,"account":null,"scopes":[],"managedToken":true}"""))
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
            val base = server.url("/")
            val source = HttpGitHubConnection(http, authority = { FilesAuthority.Paired(base) { it.header("Authorization", "Bearer tthr_test") } })
            show(source, origin = serverOrigin(base.toString())!!)
            assertEquals("/api/github/connection", server.takeRequest(10, TimeUnit.SECONDS)!!.path)

            tag(GitHubTags.Token).performScrollTo().performTextReplacement("  ${GitHubFixtures.FAKE_TOKEN} \n")
            tag(GitHubTags.Verify).performScrollTo().performClick()
            val sent = server.takeRequest(10, TimeUnit.SECONDS)!!
            assertEquals("POST", sent.method)
            assertEquals("/api/github/connection/token", sent.path)
            assertEquals("""{"token":"${GitHubFixtures.FAKE_TOKEN}"}""", sent.body.readUtf8())
            compose.waitUntil(10_000) { sectionTexts().contains(GitHubCopy.TOKEN_SAVED) }
            assertEquals("", c.tokenInput)
            assertEquals("/api/github/connection", server.takeRequest(10, TimeUnit.SECONDS)!!.path)
        } finally {
            server.shutdown()
        }
    }

    @Test fun aRefusedTokenShowsTheServersSentenceUnderTheRow() {
        val gh = FakeGitHub()
        gh.saves += SecurityResult.Refused(400, "That token could not be verified. Check the value and its scopes.", ServerFixtures.ORIGIN)
        show(gh)
        tag(GitHubTags.Token).performScrollTo().performTextReplacement(GitHubFixtures.FAKE_TOKEN)
        tag(GitHubTags.Verify).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.TokenError) }
        val t = sectionTexts()
        assertEquals("That token could not be verified. Check the value and its scopes.", t.last())
        assertFalse(t.contains(GitHubCopy.TOKEN_SAVED))
    }

    // ---- the device flow ------------------------------------------------------------------------------

    @Test fun theDeviceFlowShowsTheCodeOpensTheDevicePageAndCancels() {
        val gh = FakeGitHub()
        val opener = RecordingOpener()
        show(gh, opener = opener)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { sectionTexts().contains("Enter ABCD-1234 at https://github.com/login/device.") }
        val t = sectionTexts()
        assertTrue(t.contains(GitHubCopy.WAITING))
        tag(GitHubTags.Start).assertIsNotEnabled()
        assertEquals(t.indexOf(GitHubCopy.CODE_TITLE) - 1, t.indexOf(GitHubCopy.WAITING))
        tag(GitHubTags.Open).performScrollTo().performClick()
        assertEquals(listOf("https://github.com/login/device"), opener.opened.map { it.url })
        tag(GitHubTags.Cancel).performScrollTo().performClick()
        compose.waitUntil(5_000) { !exists(GitHubTags.CodeRow) }
        assertEquals(1, gh.count("cancel@${ServerFixtures.ORIGIN}"))
        assertTrue(sectionTexts().contains(GitHubCopy.START))
    }

    @Test fun beforeGhPrintsTheCodeItSaysItIsWaitingAndOpensTheCanonicalPage() {
        val gh = FakeGitHub()
        repeat(1_000) { gh.polls += SecurityResult.Ok(GitHubFixtures.pending(), ServerFixtures.ORIGIN, null) }
        val opener = RecordingOpener()
        show(gh, opener = opener)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.CodeRow) }
        assertTrue(sectionTexts().contains(GitHubCopy.WAITING_CODE))
        tag(GitHubTags.Open).performScrollTo().performClick()
        assertEquals(listOf(GitHubCopy.DEFAULT_DEVICE_URL), opener.opened.map { it.url })
        tag(GitHubTags.Cancel).performScrollTo().performClick()
    }

    /**
     * r2 (security review): the sentence names the address Open launches (the parsed link's canonical
     * URL, not the server's raw spelling), and its host is drawn beside Open.
     */
    @Test fun theSentenceNamesWhatOpenLaunchesWithItsHostBesideOpen() {
        val gh = FakeGitHub()
        repeat(1_000) { gh.polls += SecurityResult.Ok(GitHubFixtures.pending("ABCD-1234", "HTTPS://GitHub.COM:443/login/device"), ServerFixtures.ORIGIN, null) }
        val opener = RecordingOpener()
        show(gh, opener = opener)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { sectionTexts().contains("Enter ABCD-1234 at https://github.com/login/device.") }
        assertTrue(sectionTexts().contains("github.com"))
        tag(GitHubTags.OpenHost).assertExists()
        tag(GitHubTags.Open).performScrollTo().performClick()
        assertEquals(listOf("https://github.com/login/device"), opener.opened.map { it.url })
        tag(GitHubTags.Cancel).performScrollTo().performClick()
    }

    /** r2: an address a browser will not open has no Open and the row says so. */
    @Test fun anAddressNoBrowserOpensHasNoOpenAndTheRowSaysSo() = assertRefused(GitHubFixtures.pending("ABCD-1234", "javascript:alert(1)"))

    /** r3 (owner rule): an address of any length is kept whole and Open launches it whole, as the web's link. */
    @Test fun aLongAddressIsOpenedWhole() {
        val long = "https://github.com/login/device?" + (0 until 2_000).joinToString("&") { "k$it=v$it" }
        assertTrue(long.length > 20_000)
        val gh = FakeGitHub()
        repeat(1_000) { gh.polls += SecurityResult.Ok(GitHubFixtures.pending("ABCD-1234", long), ServerFixtures.ORIGIN, null) }
        val opener = RecordingOpener()
        show(gh, opener = opener)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { sectionTexts().any { it.startsWith("Enter ABCD-1234") } }
        assertFalse(exists(GitHubTags.LinkRefused))
        val sentence = sectionTexts().first { it.startsWith("Enter ABCD-1234") }
        assertEquals("the sentence names the whole address", "Enter ABCD-1234 at $long.", sentence)
        tag(GitHubTags.Open).performScrollTo().performClick()
        assertEquals(listOf(long), opener.opened.map { it.url })
        tag(GitHubTags.Cancel).performScrollTo().performClick()
    }

    private fun assertRefused(poll: com.tether.app.client.GitHubDevicePoll) {
        val gh = FakeGitHub()
        repeat(1_000) { gh.polls += SecurityResult.Ok(poll, ServerFixtures.ORIGIN, null) }
        val opener = RecordingOpener()
        show(gh, opener = opener)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.LinkRefused) }
        val t = sectionTexts()
        assertTrue(t.contains("Enter ABCD-1234."))
        assertTrue(t.contains(GitHubCopy.LINK_REFUSED))
        assertFalse(exists(GitHubTags.Open))
        assertFalse(exists(GitHubTags.OpenHost))
        assertTrue(opener.opened.isEmpty())
        tag(GitHubTags.Cancel).performScrollTo().performClick()
        compose.waitUntil(5_000) { !exists(GitHubTags.CodeRow) }
    }

    @Test fun aLinkNoBrowserOpensIsSaid() {
        val gh = FakeGitHub()
        show(gh, opener = RecordingOpener(opens = false))
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.Open) }
        tag(GitHubTags.Open).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.OpenNote) }
        assertTrue(texts().contains(GitHubCopy.LINK_UNOPENED))
        tag(GitHubTags.Cancel).performScrollTo().performClick()
    }

    @Test fun aFailedDeviceFlowSaysWhyUnderItsRow() {
        val gh = FakeGitHub()
        gh.polls += SecurityResult.Ok(com.tether.app.client.GitHubDevicePoll(true, com.tether.app.client.GitHubDeviceStatus.Error, null, null, "gh auth login exited with code 1."), ServerFixtures.ORIGIN, null)
        show(gh)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.DeviceError) }
        val t = sectionTexts()
        assertTrue(t.contains("gh auth login exited with code 1."))
        assertFalse(exists(GitHubTags.CodeRow))
        // The flow is over: Start device flow is offered again.
        assertTrue(t.contains(GitHubCopy.START))
        assertEquals(t.indexOf(GitHubCopy.START) + 1, t.indexOf("gh auth login exited with code 1."))
    }

    @Test fun aRefusedStartIsSaidUnderTheStatus() {
        val gh = FakeGitHub()
        gh.starts += SecurityResult.Refused(409, "A GitHub login is already in progress. Cancel it first.", ServerFixtures.ORIGIN)
        show(gh)
        tag(GitHubTags.Start).performScrollTo().performClick()
        compose.waitUntil(5_000) { exists(GitHubTags.ActionError) }
        val t = sectionTexts()
        assertEquals(t.indexOf(GitHubCopy.RECHECK) + 1, t.indexOf("A GitHub login is already in progress. Cancel it first."))
    }
}
