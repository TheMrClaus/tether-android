package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.tether.app.client.GitHubIssue
import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubPullRequest
import com.tether.app.client.GitHubPullRequestsList
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.GitHubWorkSource
import com.tether.app.client.SecurityResult
import com.tether.app.protocol.helpers.AttachmentDraft
import com.tether.app.ui.theme.TetherTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.4: the attach sheet's "Add issue or PR" (components/attach-sheet.tsx 90fbb9f :189-330) and what a
 * pick attaches (components/chat-view.tsx :2695-2723): the row after Paste image, the GitHub view
 * (both lists read together, kept for the folder, the web's one failure sentence, Try again, no
 * repository, none, the search), and a pick staged as `issue-N.md` / `pr-N.md` with the prompt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class AttachGitHubTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val origin = "https://tether.test:443"
    private val cwd = "/srv/ws/tether"

    private val issues = GitHubIssuesList("octo/tether", listOf(GitHubIssue(60, "Add the GitHub issues button", "Body"), GitHubIssue(61, "Sidebar flickers")))
    private val pulls = GitHubPullRequestsList("octo/tether", listOf(GitHubPullRequest(76, "Pull requests tab", "b", headRefName = "f", baseRefName = "main"), GitHubPullRequest(160, "Draft row", isDraft = true)))

    private inner class Reads : GitHubWorkSource {
        var issuesAnswer: SecurityResult<GitHubIssuesList> = SecurityResult.Ok(issues, origin, null)
        var pullsAnswer: SecurityResult<GitHubPullRequestsList> = SecurityResult.Ok(pulls, origin, null)
        val reads = CopyOnWriteArrayList<String>()
        override suspend fun issues(origin: String, cwd: String): SecurityResult<GitHubIssuesList> {
            reads += "issues $origin $cwd"
            return issuesAnswer
        }
        override suspend fun pullRequests(origin: String, cwd: String): SecurityResult<GitHubPullRequestsList> {
            reads += "pulls $origin $cwd"
            return pullsAnswer
        }
    }

    @After fun tearDown() = job.cancel()

    // ---- the controller -------------------------------------------------------------------------

    @Test fun bothListsAreReadTogetherAndKeptForTheFolder() {
        val reads = Reads()
        val c = AttachGitHubController(reads, origin, scope)
        c.open(cwd)
        assertEquals(listOf("issues $origin $cwd", "pulls $origin $cwd"), reads.reads.toList())
        assertEquals("octo/tether", c.repository)
        assertEquals(issues.issues, c.issues)
        assertEquals(pulls.pullRequests, c.pullRequests)
        assertFalse(c.loading)
        // :193-195: re-opening for the same folder does not read again; another folder does.
        c.open(cwd)
        assertEquals(2, reads.reads.size)
        c.open("/srv/ws/other")
        assertEquals(4, reads.reads.size)
    }

    @Test fun anyRefusalIsTheWebsOneSentenceAndARetryReadsAgain() {
        val reads = Reads()
        reads.pullsAnswer = SecurityResult.Refused(502, "GitHub pull requests could not be loaded. Check the server's gh authentication.", origin)
        val c = AttachGitHubController(reads, origin, scope)
        c.open(cwd)
        assertEquals(GitHubWorkCopy.BOTH_FAILED, c.error)
        reads.pullsAnswer = SecurityResult.Ok(pulls, origin, null)
        c.open(cwd)
        assertEquals("", c.error)
        assertEquals(4, reads.reads.size)
        reads.issuesAnswer = SecurityResult.Unavailable(null, origin)
        c.open("/srv/ws/b")
        assertEquals(GitHubWorkCopy.BOTH_FAILED, c.error)
    }

    @Test fun nativeFailuresAreSaidInTheAppsWords() {
        val reads = Reads()
        reads.issuesAnswer = SecurityResult.Blocked(302, origin)
        val c = AttachGitHubController(reads, origin, scope)
        c.open(cwd)
        assertEquals(GitHubWorkCopy.blocked(302), c.error)
        val signedOut = AttachGitHubController(reads, null, scope)
        signedOut.open(cwd)
        assertEquals(GitHubWorkCopy.SIGNED_OUT, signedOut.error)
        assertEquals(2, reads.reads.size)
    }

    @Test fun noFolderIsSaidWithoutAnyRead() {
        val reads = Reads()
        val c = AttachGitHubController(reads, origin, scope)
        c.open("")
        assertEquals(GitHubWorkCopy.NO_WORKSPACE, c.error)
        assertTrue(reads.reads.isEmpty())
    }

    @Test fun theRepositoryFallsBackToThePullRequestsAnswer() {
        val reads = Reads()
        reads.issuesAnswer = SecurityResult.Ok(GitHubIssuesList(null, emptyList()), origin, null)
        val c = AttachGitHubController(reads, origin, scope)
        c.open(cwd)
        assertEquals("octo/tether", c.repository)
    }

    @Test fun theSearchMatchesTheNumberOrTheTitle() {
        assertTrue(matchesGitHubQuery("  ", 60, "x"))
        assertTrue(matchesGitHubQuery("6", 60, "x"))
        assertTrue(matchesGitHubQuery(" SIDEBAR ", 61, "Sidebar flickers"))
        assertFalse(matchesGitHubQuery("7", 60, "Add"))
    }

    // ---- what a pick attaches -------------------------------------------------------------------

    @Test fun aPickIsStagedAsTheItemsPromptInMarkdown() {
        val ids = AtomicLong(1)
        val issue = GitHubWorkAttachmentSource("octo/tether", GitHubWorkSelection.Issue(issues.issues[0]))
        val pr = GitHubWorkAttachmentSource("octo/tether", GitHubWorkSelection.PullRequest(pulls.pullRequests[0]))
        val result = AttachmentIntake.intake(listOf(issue, pr), emptyList(), { ids.getAndIncrement() })
        assertEquals(emptyList<String>(), result.flashes)
        val (a, b) = result.added
        assertEquals("issue-60.md", a.attachment.name)
        assertEquals("text/markdown", a.attachment.mediaType)
        assertEquals(GitHubWorkPrompt.issue("octo/tether", issues.issues[0]), String(java.util.Base64.getDecoder().decode(a.attachment.data), Charsets.UTF_8))
        assertEquals("pr-76.md", b.attachment.name)
        assertEquals(GitHubWorkPrompt.pullRequest("octo/tether", pulls.pullRequests[0]), String(java.util.Base64.getDecoder().decode(b.attachment.data), Charsets.UTF_8))
    }

    @Test fun aPickPastTheCountIsRefusedInTheWebsWords() {
        val ids = AtomicLong(1)
        val existing = (1..AttachmentDraft.MAX_ATTACHMENTS).map {
            com.tether.app.client.StagedAttachment(ids.getAndIncrement(), com.tether.app.protocol.Attachment("f$it", "text/plain", "eA=="), 1)
        }
        val result = AttachmentIntake.intake(listOf(GitHubWorkAttachmentSource("o/r", GitHubWorkSelection.Issue(issues.issues[1]))), existing, { ids.getAndIncrement() })
        assertEquals(listOf("You can attach up to ${AttachmentDraft.MAX_ATTACHMENTS} files at once."), result.flashes)
        assertTrue(result.added.isEmpty())
    }

    // ---- the sheet ------------------------------------------------------------------------------

    private val picks = CopyOnWriteArrayList<Pair<String, GitHubWorkSelection>>()
    private var dismissed = 0

    private fun show(reads: Reads, folder: String = cwd, github: Boolean = true) {
        val c = AttachGitHubController(reads, origin, scope)
        rule.setContent {
            TetherTheme {
                AttachSheet(
                    onDismiss = { dismissed++ },
                    onPickImages = {},
                    onTakePhoto = {},
                    onPasteImage = {},
                    onPickFiles = {},
                    github = if (github) AttachSheetGitHub(folder, c) { repo, selection -> picks += repo to selection } else null,
                )
            }
        }
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        rule.waitUntil(5_000) { exists(tag) }
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun rowLabels(): List<String> = rule.onNodeWithTag(ATTACH_SHEET_TAG, useUnmergedTree = true).fetchSemanticsNode().children
        .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    @Test fun theRowSitsAfterPasteImageAndOpensTheListThenAPickAttachesAndCloses() {
        val reads = Reads()
        show(reads)
        rule.waitUntil(5_000) { exists(ATTACH_SHEET_TAG) }
        assertEquals(listOf(ATTACH_ROW_IMAGES, ATTACH_ROW_CAMERA, ATTACH_ROW_PASTE, GitHubWorkCopy.ATTACH_ROW, ATTACH_ROW_FILES), rowLabels())
        tap(ATTACH_ROW_GITHUB_TAG)
        rule.waitUntil(5_000) { exists(ATTACH_GITHUB_TAG) }
        assertTrue(shows(GitHubWorkCopy.ATTACH_TITLE))
        assertEquals(0, dismissed)
        val draft = GitHubWorkSelection.PullRequest(pulls.pullRequests[1])
        rule.waitUntil(5_000) { exists(attachGitHubRowTag(draft)) }
        assertTrue(rule.onAllNodesWithContentDescription("#60 · Add the GitHub issues button").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("#160 · Draft row").fetchSemanticsNodes().isNotEmpty())
        // The search: by number or title, then its own empty words.
        rule.onNodeWithTag(ATTACH_GITHUB_SEARCH_TAG, useUnmergedTree = true).performTextReplacement("16")
        rule.waitUntil(5_000) { !exists(attachGitHubRowTag(GitHubWorkSelection.Issue(issues.issues[0]))) }
        assertTrue(exists(attachGitHubRowTag(draft)))
        rule.onNodeWithTag(ATTACH_GITHUB_SEARCH_TAG, useUnmergedTree = true).performTextReplacement("nothing like it")
        rule.waitUntil(5_000) { shows(GitHubWorkCopy.NO_MATCHES) }
        rule.onNodeWithTag(ATTACH_GITHUB_SEARCH_TAG, useUnmergedTree = true).performTextReplacement("")
        tap(attachGitHubRowTag(GitHubWorkSelection.Issue(issues.issues[0])))
        rule.waitUntil(5_000) { picks.isNotEmpty() }
        assertEquals(listOf("octo/tether" to GitHubWorkSelection.Issue(issues.issues[0])), picks.toList())
        assertEquals(1, dismissed)
    }

    @Test fun backReturnsToTheRows() {
        show(Reads())
        tap(ATTACH_ROW_GITHUB_TAG)
        rule.waitUntil(5_000) { exists(ATTACH_GITHUB_TAG) }
        rule.onAllNodesWithContentDescription(GitHubWorkCopy.ATTACH_BACK)[0].performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { exists(ATTACH_SHEET_TAG) }
        assertFalse(exists(ATTACH_GITHUB_TAG))
    }

    @Test fun aFailureOffersTryAgain() {
        val reads = Reads()
        reads.issuesAnswer = SecurityResult.Refused(400, "That working folder is not available.", origin)
        show(reads)
        tap(ATTACH_ROW_GITHUB_TAG)
        rule.waitUntil(5_000) { shows(GitHubWorkCopy.BOTH_FAILED) }
        reads.issuesAnswer = SecurityResult.Ok(GitHubIssuesList(null, emptyList()), origin, null)
        reads.pullsAnswer = SecurityResult.Ok(GitHubPullRequestsList(null, emptyList()), origin, null)
        tap(ATTACH_GITHUB_RETRY_TAG)
        rule.waitUntil(5_000) { shows(GitHubWorkCopy.NO_REPO) }
    }

    @Test fun aRepositoryWithNothingOpenSaysSo() {
        val reads = Reads()
        reads.issuesAnswer = SecurityResult.Ok(GitHubIssuesList("o/r", emptyList()), origin, null)
        reads.pullsAnswer = SecurityResult.Ok(GitHubPullRequestsList("o/r", emptyList()), origin, null)
        show(reads)
        tap(ATTACH_ROW_GITHUB_TAG)
        rule.waitUntil(5_000) { shows(GitHubWorkCopy.NONE_OPEN) }
    }

    @Test fun withoutTheReadsThereIsNoRow() {
        show(Reads(), github = false)
        rule.waitUntil(5_000) { exists(ATTACH_SHEET_TAG) }
        assertEquals(listOf(ATTACH_ROW_IMAGES, ATTACH_ROW_CAMERA, ATTACH_ROW_PASTE, ATTACH_ROW_FILES), rowLabels())
    }
}
