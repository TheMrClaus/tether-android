package com.tether.app.ui.draft

import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubPullRequestsList
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.SecurityResult
import com.tether.app.ui.chat.GitHubWorkCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.4: [GitHubWorkController] against components/github-work-dialog.tsx 90fbb9f :45-168: when a tab
 * is read (opening reads the initial tab every time; a tab is read on its first showing), one read at
 * a time, the states (loading, the server's sentence or the fallback, no repository, the list), the
 * row's menu, the work hand-off and the copy flags.
 */
class GitHubWorkControllerTest {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private val source = FakeGitHubWork()
    private val origin = DraftFixtures.ORIGIN
    private val cwd = "/srv/ws/tether"

    private fun controller(o: String? = origin) = GitHubWorkController(source, o, scope)

    @After fun tearDown() = job.cancel()

    @Test fun nothingOpensOrIsReadWithoutAFolderOrWhileCreating() {
        val c = controller()
        assertFalse(c.openDialog(GitHubWorkTab.Issues, ""))
        assertFalse(c.openDialog(GitHubWorkTab.PullRequests, cwd, disabled = true))
        assertFalse(c.open)
        assertTrue(source.issueReads.isEmpty() && source.pullReads.isEmpty())
    }

    @Test fun openingReadsItsTabForTheFolderAndTheServer() {
        val c = controller()
        assertTrue(c.openDialog(GitHubWorkTab.Issues, cwd))
        assertTrue(c.open)
        assertEquals(GitHubWorkTab.Issues, c.tab)
        assertEquals(listOf(origin to cwd), source.issueReads.toList())
        assertEquals(GitHubTabState(response = GitHubFixtures.issues, cwd = cwd), c.issues)
        assertTrue(source.pullReads.isEmpty())
    }

    @Test fun aTabIsReadOnItsFirstShowingOnlyButOpeningReadsAgain() {
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        c.selectTab(GitHubWorkTab.PullRequests)
        assertEquals(1, source.pullReads.size)
        assertEquals(GitHubFixtures.pullRequests, c.pullRequests.response)
        c.selectTab(GitHubWorkTab.Issues)
        c.selectTab(GitHubWorkTab.PullRequests)
        assertEquals(1, source.issueReads.size)
        assertEquals(1, source.pullReads.size)
        // :96-101: openDialog always reads its tab.
        c.close()
        c.openDialog(GitHubWorkTab.PullRequests, cwd)
        assertEquals(2, source.pullReads.size)
        assertEquals(1, source.issueReads.size)
    }

    @Test fun loadingIsShownUntilTheAnswerLands() {
        val hold = CompletableDeferred<SecurityResult<GitHubIssuesList>>()
        source.holdIssues = hold
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        assertTrue(c.issues.loading)
        // A tab that is loading is not read again when shown.
        c.selectTab(GitHubWorkTab.Issues)
        assertEquals(1, source.issueReads.size)
        hold.complete(SecurityResult.Ok(GitHubIssuesList(null, emptyList()), origin, null))
        assertEquals(GitHubTabState(response = GitHubIssuesList(null, emptyList()), cwd = cwd), c.issues)
    }

    @Test fun theServersSentenceOrTheFallback() {
        val c = controller()
        source.issuesAnswer = SecurityResult.Refused(502, GitHubFixtures.AUTH_ERROR, origin)
        c.openDialog(GitHubWorkTab.Issues, cwd)
        assertEquals(GitHubFixtures.AUTH_ERROR, c.issues.error)
        assertTrue(GitHubWorkCopy.needsSetup(c.issues.error))
        source.issuesAnswer = SecurityResult.Refused(400, "That working folder is not available.", origin)
        c.retry()
        assertEquals("That working folder is not available.", c.issues.error)
        assertFalse(GitHubWorkCopy.needsSetup(c.issues.error))
        source.issuesAnswer = SecurityResult.Refused(500, "", origin)
        c.retry()
        assertEquals(GitHubWorkCopy.ISSUES_FAILED, c.issues.error)
        source.pullsAnswer = SecurityResult.Unavailable(null, origin)
        c.selectTab(GitHubWorkTab.PullRequests)
        assertEquals(GitHubWorkCopy.PULL_REQUESTS_FAILED, c.pullRequests.error)
        source.pullsAnswer = SecurityResult.Blocked(302, origin)
        c.retry()
        assertEquals(GitHubWorkCopy.blocked(302), c.pullRequests.error)
        source.pullsAnswer = SecurityResult.SignedOut(origin)
        c.retry()
        assertEquals(GitHubWorkCopy.SIGNED_OUT, c.pullRequests.error)
        source.pullsAnswer = SecurityResult.NotSent("https://other.test")
        c.retry()
        assertEquals(GitHubWorkCopy.NOT_SENT_OTHER, c.pullRequests.error)
        source.throwOnRead = true
        c.retry()
        assertEquals(GitHubWorkCopy.PULL_REQUESTS_FAILED, c.pullRequests.error)
    }

    @Test fun anErrorTabIsNotReadAgainOnShowingButTryAgainReadsIt() {
        val c = controller()
        source.issuesAnswer = SecurityResult.Refused(502, GitHubFixtures.AUTH_ERROR, origin)
        c.openDialog(GitHubWorkTab.Issues, cwd)
        c.selectTab(GitHubWorkTab.PullRequests)
        c.selectTab(GitHubWorkTab.Issues)
        assertEquals(1, source.issueReads.size)
        c.retry()
        assertEquals(2, source.issueReads.size)
    }

    @Test fun signedOutReadsNothing() {
        val c = controller(o = null)
        c.openDialog(GitHubWorkTab.Issues, cwd)
        assertEquals(GitHubWorkCopy.SIGNED_OUT, c.issues.error)
        assertTrue(source.issueReads.isEmpty())
    }

    @Test fun aNewReadStopsTheOneInFlightAndLeavesItsTabToBeReadAgain() {
        val issuesHeld = CompletableDeferred<SecurityResult<GitHubIssuesList>>()
        source.holdIssues = issuesHeld
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        c.selectTab(GitHubWorkTab.PullRequests)
        // The issues read was cancelled: its tab is empty, not stuck on "Loading…" (the web's single AbortController).
        assertEquals(GitHubTabState<GitHubIssuesList>(), c.issues)
        issuesHeld.complete(SecurityResult.Ok(GitHubFixtures.issues, origin, null))
        assertEquals(GitHubTabState<GitHubIssuesList>(), c.issues)
        source.holdIssues = null
        c.selectTab(GitHubWorkTab.Issues)
        assertEquals(2, source.issueReads.size)
        assertEquals(GitHubFixtures.issues, c.issues.response)
    }

    @Test fun closingStopsTheReadAndAnswersLandNowhere() {
        val held = CompletableDeferred<SecurityResult<GitHubPullRequestsList>>()
        source.holdPulls = held
        val c = controller()
        c.openDialog(GitHubWorkTab.PullRequests, cwd)
        c.close()
        assertFalse(c.open)
        held.complete(SecurityResult.Ok(GitHubFixtures.pullRequests, origin, null))
        assertEquals(GitHubTabState<GitHubPullRequestsList>(), c.pullRequests)
    }

    @Test fun anAnswerForAnotherFolderIsReadAgainForThisOne() {
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, "/srv/ws/a")
        c.selectTab(GitHubWorkTab.PullRequests)
        c.close()
        c.openDialog(GitHubWorkTab.Issues, "/srv/ws/b")
        c.selectTab(GitHubWorkTab.PullRequests)
        assertEquals(listOf(origin to "/srv/ws/a", origin to "/srv/ws/b"), source.pullReads.toList())
    }

    @Test fun oneMenuAtATimeAndAReadClosesIt() {
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        c.toggleMenu(60)
        assertEquals(60L, c.openMenu)
        c.toggleMenu(61)
        assertEquals(61L, c.openMenu)
        c.toggleMenu(61)
        assertNull(c.openMenu)
        c.toggleMenu(60)
        c.selectTab(GitHubWorkTab.PullRequests)
        assertNull(c.openMenu)
    }

    @Test fun workAndReviewHandTheComposerThePromptAndTheFolderAndClose() {
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        val issue = GitHubFixtures.issues.issues[0]
        assertEquals(GitHubWork(GitHubWorkPrompt.issue(GitHubFixtures.REPO, issue), cwd), c.workOn(issue))
        assertFalse(c.open)
        c.openDialog(GitHubWorkTab.PullRequests, cwd)
        val pr = GitHubFixtures.pullRequests.pullRequests[0]
        assertEquals(GitHubWork(GitHubWorkPrompt.pullRequest(GitHubFixtures.REPO, pr), cwd), c.review(pr))
        assertFalse(c.open)
    }

    @Test fun withoutARepositoryThereIsNoPrompt() {
        source.issuesAnswer = SecurityResult.Ok(GitHubIssuesList(null, emptyList()), origin, null)
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        assertNull(c.workOn(GitHubFixtures.issues.issues[0]))
        assertNull(c.promptFor(GitHubFixtures.issues.issues[0]))
        assertTrue(c.open)
    }

    @Test fun theCopyFlagsFollowTheClipboardAndAReadClearsThem() {
        val c = controller()
        c.openDialog(GitHubWorkTab.Issues, cwd)
        c.copied(60, ok = true)
        assertEquals(60L, c.copiedNumber)
        assertNull(c.copyErrorNumber)
        c.copied(61, ok = false)
        assertNull(c.copiedNumber)
        assertEquals(61L, c.copyErrorNumber)
        c.retry()
        assertNull(c.copiedNumber)
        assertNull(c.copyErrorNumber)
    }

    @Test fun ghAuthenticationIsMatchedAsTheWebsRegexDoes() {
        assertTrue(GitHubWorkCopy.needsSetup("Check the server's GH Authentication."))
        assertFalse(GitHubWorkCopy.needsSetup("gh  authentication"))
        assertFalse(GitHubWorkCopy.needsSetup("GitHub returned an invalid issue list."))
    }
}
