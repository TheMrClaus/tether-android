package com.tether.app.ui.draft

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.SecurityResult
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.MainShell
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.chat.GitHubWorkCopy
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.settings.SettingsDialogTags
import com.tether.app.ui.settings.SettingsTab
import com.tether.app.ui.theme.TetherTheme
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.4: the new-session sheet's "GitHub issues" / "Pull requests" buttons and their dialog over the
 * real shell, view model and draft engine (components/github-work-dialog.tsx and draft-composer.tsx
 * :347-355, tether 90fbb9f): enabled exactly when the web's are, the two tabs read on first showing,
 * the rows, the menu (the composer filled and the folder set, the link opened, the prompt copied),
 * and the gh-authentication error's way into Settings → Advanced (the GitHub connection).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GitHubWorkDialogBehaviourTest {
    val tmp = TemporaryFolder()
    val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(tmp).around(rule)

    private val job = Job()
    private val prefs: UiPrefs by lazy {
        UiPrefs.on(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(tmp.root, "ui.preferences_pb") })
    }
    private val client = DraftTestClient()
    private val vm by lazy { TetherViewModel(client) }
    private val composer get() = vm.draftComposer
    private val opened = CopyOnWriteArrayList<String>()
    private val opener = LinkOpener { _, href, _: Color -> opened += href }

    private val window = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = IntSize(412, 915)
    }

    @After fun closeStore() = runBlocking { job.cancel() }

    private fun openSheet() {
        rule.setContent {
            TetherTheme {
                CompositionLocalProvider(LocalWindowInfo provides window, LocalLinkOpener provides opener) { MainShell(vm, prefs) }
            }
        }
        rule.runOnUiThread { vm.openDraft() }
        awaitTag(DraftComposerTags.Sheet)
        until("the folder is seeded") { formCwd().isNotEmpty() }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(what: String, condition: () -> Boolean) = try {
        rule.waitUntil(5_000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            condition()
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("timed out waiting for: $what", e)
    }

    private fun awaitTag(tag: String) = until("tag $tag") { exists(tag) }

    private fun awaitGone(tag: String) = until("no tag $tag") { !exists(tag) }

    private fun tap(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun shows(text: String) = rule.onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun said(tag: String): String = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun formCwd() = (composer.state.value.form["cwd"] as? JsStr)?.value.orEmpty()

    @Test
    fun theButtonsAreEnabledWithAFolderAndOpenTheirTab() {
        openSheet()
        rule.onNodeWithTag(GitHubWorkTags.IssuesButton, useUnmergedTree = true).assertIsEnabled()
        rule.onNodeWithTag(GitHubWorkTags.PullRequestsButton, useUnmergedTree = true).assertIsEnabled()
        assertEquals(GitHubWorkCopy.ISSUES_BUTTON, said(GitHubWorkTags.IssuesButton))
        assertEquals(GitHubWorkCopy.PULL_REQUESTS_BUTTON, said(GitHubWorkTags.PullRequestsButton))
        tap(GitHubWorkTags.PullRequestsButton)
        awaitTag(GitHubWorkTags.Dialog)
        rule.onNodeWithTag(GitHubWorkTags.tab(GitHubWorkTab.PullRequests), useUnmergedTree = true).assertIsSelected()
        until("the pull requests were read for the folder on the server signed in to") { client.github.pullReads.toList() == listOf(com.tether.app.client.serverOrigin(DraftFixtures.SERVER) to formCwd()) }
        assertTrue(client.github.issueReads.isEmpty())
        // The rows: the draft glyph's row, head → base, "unknown branch" without a head.
        awaitTag(GitHubWorkTags.row(76))
        assertTrue(said(GitHubWorkTags.row(76)).contains("#76 · Pull requests tab, feature/prs → main · review"))
        assertTrue(said(GitHubWorkTags.row(78)).contains("#78 · WIP: attach sheet row, attach-row → main"))
        assertTrue(said(GitHubWorkTags.row(79)).contains("#79 · Branch gone, unknown branch"))
        assertTrue(shows(GitHubFixtures.REPO))
        // The Issues tab is read on its first showing.
        tap(GitHubWorkTags.tab(GitHubWorkTab.Issues))
        until("the issues were read") { client.github.issueReads.size == 1 }
        awaitTag(GitHubWorkTags.row(60))
        assertTrue(said(GitHubWorkTags.row(60)).contains("#60 · Add the GitHub issues button, enhancement · ui"))
        assertTrue(said(GitHubWorkTags.row(61)).contains("#61 · Sidebar flickers on resume, No labels"))
        tap(GitHubWorkTags.Footer)
        awaitGone(GitHubWorkTags.Dialog)
    }

    @Test
    fun theButtonsAreDisabledWithoutAFolderOrWhileCreating() {
        rule.setContent {
            TetherTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Row { GitHubWorkButtons("", creating = false) { error("disabled") } }
                }
            }
        }
        rule.onNodeWithTag(GitHubWorkTags.IssuesButton, useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithTag(GitHubWorkTags.PullRequestsButton, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun theButtonsAreDisabledWhileACreateIsInFlight() {
        rule.setContent {
            TetherTheme { androidx.compose.foundation.layout.Row { GitHubWorkButtons("/srv/ws", creating = true) { error("disabled") } } }
        }
        rule.onNodeWithTag(GitHubWorkTags.IssuesButton, useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithTag(GitHubWorkTags.PullRequestsButton, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun workOnThisIssueFillsTheComposerAndSetsTheFolder() {
        openSheet()
        val folder = formCwd()
        tap(GitHubWorkTags.IssuesButton)
        tap(GitHubWorkTags.row(60))
        awaitTag(GitHubWorkTags.Work)
        assertEquals("Expanded", rule.onNodeWithTag(GitHubWorkTags.row(60), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        tap(GitHubWorkTags.Work)
        awaitGone(GitHubWorkTags.Dialog)
        val expected = GitHubWorkPrompt.issue(GitHubFixtures.REPO, GitHubFixtures.issues.issues[0])
        until("the composer holds the prompt") { composer.state.value.text == expected }
        assertEquals(folder, formCwd())
    }

    @Test
    fun reviewThisPrFillsTheComposer() {
        openSheet()
        tap(GitHubWorkTags.PullRequestsButton)
        tap(GitHubWorkTags.row(76))
        assertEquals(GitHubWorkCopy.REVIEW_PR, said(GitHubWorkTags.Work))
        tap(GitHubWorkTags.Work)
        awaitGone(GitHubWorkTags.Dialog)
        val expected = GitHubWorkPrompt.pullRequest(GitHubFixtures.REPO, GitHubFixtures.pullRequests.pullRequests[0])
        until("the composer holds the prompt") { composer.state.value.text == expected }
    }

    @Test
    fun viewOnGitHubOpensTheIssuesAndThePullsAddress() {
        openSheet()
        tap(GitHubWorkTags.IssuesButton)
        tap(GitHubWorkTags.row(61))
        tap(GitHubWorkTags.View)
        until("the issue link opened") { opened.toList() == listOf("https://github.com/octo/tether/issues/61") }
        tap(GitHubWorkTags.tab(GitHubWorkTab.PullRequests))
        tap(GitHubWorkTags.row(78))
        tap(GitHubWorkTags.View)
        until("the pull link opened") { opened.last() == "https://github.com/octo/tether/pull/78" }
        // The link leaves the dialog up, as the web's `<a target="_blank">`.
        assertTrue(exists(GitHubWorkTags.Dialog))
    }

    @Test
    fun copyPromptPutsThePromptOnTheClipboard() {
        openSheet()
        tap(GitHubWorkTags.IssuesButton)
        tap(GitHubWorkTags.row(60))
        assertEquals(GitHubWorkCopy.COPY_PROMPT, said(GitHubWorkTags.Copy))
        tap(GitHubWorkTags.Copy)
        until("Copied prompt") { said(GitHubWorkTags.Copy) == GitHubWorkCopy.COPIED_PROMPT }
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals(GitHubWorkPrompt.issue(GitHubFixtures.REPO, GitHubFixtures.issues.issues[0]), clipboard.primaryClip!!.getItemAt(0).text.toString())
        // The composer is untouched by a copy.
        assertEquals("", composer.state.value.text)
    }

    @Test
    fun anAuthErrorOffersSetUpWhichOpensTheGitHubSettings() {
        client.github.issuesAnswer = SecurityResult.Refused(502, GitHubFixtures.AUTH_ERROR, DraftFixtures.ORIGIN)
        openSheet()
        tap(GitHubWorkTags.IssuesButton)
        awaitTag(GitHubWorkTags.Retry)
        assertTrue(shows(GitHubFixtures.AUTH_ERROR))
        tap(GitHubWorkTags.SetUp)
        awaitGone(GitHubWorkTags.Dialog)
        awaitTag(SettingsDialogTags.Dialog)
        rule.onNodeWithTag(SettingsDialogTags.tab(SettingsTab.Advanced)).assertIsSelected()
    }

    @Test
    fun anotherErrorOffersOnlyTryAgainWhichReadsAgain() {
        client.github.issuesAnswer = SecurityResult.Refused(400, "That working folder is not available.", DraftFixtures.ORIGIN)
        openSheet()
        tap(GitHubWorkTags.IssuesButton)
        awaitTag(GitHubWorkTags.Retry)
        assertTrue(shows("That working folder is not available."))
        assertTrue(!exists(GitHubWorkTags.SetUp))
        client.github.issuesAnswer = SecurityResult.Ok(GitHubIssuesList(null, emptyList()), DraftFixtures.ORIGIN, null)
        tap(GitHubWorkTags.Retry)
        until("no repository") { shows(GitHubWorkCopy.NO_REPO) }
        assertEquals(2, client.github.issueReads.size)
    }

    @Test
    fun anEmptyListSaysSoForTheRepository() {
        client.github.pullsAnswer = SecurityResult.Ok(com.tether.app.client.GitHubPullRequestsList(GitHubFixtures.REPO, emptyList()), DraftFixtures.ORIGIN, null)
        openSheet()
        tap(GitHubWorkTags.PullRequestsButton)
        until("empty") { shows(GitHubWorkCopy.noOpenPullRequests(GitHubFixtures.REPO)) }
    }
}
