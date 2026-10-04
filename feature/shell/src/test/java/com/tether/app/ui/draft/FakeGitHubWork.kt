package com.tether.app.ui.draft

import com.tether.app.client.GitHubIssue
import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubLabel
import com.tether.app.client.GitHubPullRequest
import com.tether.app.client.GitHubPullRequestsList
import com.tether.app.client.GitHubWorkSource
import com.tether.app.client.SecurityResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** T8.4: the reads' fixtures (server.mjs 90fbb9f :8181-8211 shapes). */
object GitHubFixtures {
    const val REPO = "octo/tether"

    val issues = GitHubIssuesList(
        REPO,
        listOf(
            GitHubIssue(60, "Add the GitHub issues button", "The composer needs it.\r\n\r\nSee the web.", listOf(GitHubLabel("enhancement", "a2eeef"), GitHubLabel("ui", "1d76db"))),
            GitHubIssue(61, "Sidebar flickers on resume", "", emptyList()),
        ),
    )

    val pullRequests = GitHubPullRequestsList(
        REPO,
        listOf(
            GitHubPullRequest(76, "Pull requests tab", "Adds the tab.", listOf(GitHubLabel("review", "fbca04")), "feature/prs", "main", isDraft = false, url = "https://github.com/octo/tether/pull/76", author = "octocat"),
            GitHubPullRequest(78, "WIP: attach sheet row", "", emptyList(), "attach-row", "main", isDraft = true),
            GitHubPullRequest(79, "Branch gone", "", emptyList(), "", "main"),
        ),
    )

    const val AUTH_ERROR = "GitHub issues could not be loaded. Check the server's gh authentication."
}

/**
 * A [GitHubWorkSource] whose answers the test sets ([issuesAnswer], [pullsAnswer]) or holds open
 * ([holdIssues] / [holdPulls]: a read waits on a [CompletableDeferred] the test completes). Records
 * every read: (origin, cwd).
 */
class FakeGitHubWork : GitHubWorkSource {
    var issuesAnswer: SecurityResult<GitHubIssuesList> = SecurityResult.Ok(GitHubFixtures.issues, DraftFixtures.ORIGIN, null)
    var pullsAnswer: SecurityResult<GitHubPullRequestsList> = SecurityResult.Ok(GitHubFixtures.pullRequests, DraftFixtures.ORIGIN, null)
    var holdIssues: CompletableDeferred<SecurityResult<GitHubIssuesList>>? = null
    var holdPulls: CompletableDeferred<SecurityResult<GitHubPullRequestsList>>? = null
    var throwOnRead = false

    val issueReads = CopyOnWriteArrayList<Pair<String, String>>()
    val pullReads = CopyOnWriteArrayList<Pair<String, String>>()

    override suspend fun issues(origin: String, cwd: String): SecurityResult<GitHubIssuesList> {
        issueReads += origin to cwd
        if (throwOnRead) throw IllegalStateException("boom")
        return holdIssues?.await() ?: issuesAnswer
    }

    override suspend fun pullRequests(origin: String, cwd: String): SecurityResult<GitHubPullRequestsList> {
        pullReads += origin to cwd
        if (throwOnRead) throw IllegalStateException("boom")
        return holdPulls?.await() ?: pullsAnswer
    }
}
