package com.tether.app.ui.draft

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.GitHubIssue
import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubPullRequest
import com.tether.app.client.GitHubPullRequestsList
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.GitHubWorkSource
import com.tether.app.client.SecurityResult
import com.tether.app.ui.chat.GitHubWorkCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** github-work-dialog.tsx `Tab` / `TABS`. */
enum class GitHubWorkTab(val label: String) {
    Issues(GitHubWorkCopy.TAB_ISSUES),
    PullRequests(GitHubWorkCopy.TAB_PULL_REQUESTS),
}

/** github-work-dialog.tsx `TabState<T>`, plus the folder the answer is for ([cwd]). */
@Immutable
data class GitHubTabState<T>(
    val response: T? = null,
    val loading: Boolean = false,
    val error: String = "",
    val cwd: String? = null,
)

/** What a row's "Work on this issue" / "Review this PR" hands the composer (the web's `onWork(prompt, cwd)`). */
@Immutable
data class GitHubWork(val prompt: String, val cwd: String)

/**
 * T8.4: GitHubWorkDialog's state and reads (components/github-work-dialog.tsx 90fbb9f :45-168) for ONE
 * server ([origin]), the web's state machine:
 * - [openDialog] (:96-101): nothing without a folder or while a create is in flight (the buttons'
 *   `disabled={disabled || !cwd}`); otherwise the dialog shows on [tab] and that tab is read (again).
 * - [selectTab] (:103-108): a tab is read the first time it is shown (no answer, no error, not loading).
 * - [fetch] (:65-94): ONE read at a time (the web's single AbortController): a new read cancels the one
 *   in flight. Each tab keeps its answer or its error ([GitHubTabState]).
 * - [toggleMenu] (:276), [copied] (:142-164): one row's menu open at a time; the copy flag per row.
 * Native, where the web would strand a tab: a cancelled read (another read, or the dialog closed) leaves
 * its tab empty rather than "Loading…" for good, so it is read again when next shown; and an answer read
 * for another folder is read again for the folder the dialog now opens on.
 */
@Stable
class GitHubWorkController(
    private val source: GitHubWorkSource,
    val origin: String?,
    private val scope: CoroutineScope,
) {
    var open: Boolean by mutableStateOf(false)
        private set
    var tab: GitHubWorkTab by mutableStateOf(GitHubWorkTab.Issues)
        private set
    var cwd: String by mutableStateOf("")
        private set
    var issues: GitHubTabState<GitHubIssuesList> by mutableStateOf(GitHubTabState())
        private set
    var pullRequests: GitHubTabState<GitHubPullRequestsList> by mutableStateOf(GitHubTabState())
        private set
    var openMenu: Long? by mutableStateOf(null)
        private set
    var copiedNumber: Long? by mutableStateOf(null)
        private set
    var copyErrorNumber: Long? by mutableStateOf(null)
        private set

    private var job: Job? = null
    private var loadingTab: GitHubWorkTab? = null
    private var seq = 0

    /** The goldens' seam: a state set for the shot (nothing is read while it stands). */
    fun seed(
        tab: GitHubWorkTab,
        cwd: String,
        issues: GitHubTabState<GitHubIssuesList> = GitHubTabState(),
        pullRequests: GitHubTabState<GitHubPullRequestsList> = GitHubTabState(),
        openMenu: Long? = null,
        copiedNumber: Long? = null,
    ) {
        this.open = true
        this.tab = tab
        this.cwd = cwd
        this.issues = issues
        this.pullRequests = pullRequests
        this.openMenu = openMenu
        this.copiedNumber = copiedNumber
    }

    /** :96-101 `openDialog`; false (nothing shown, nothing read) without a folder or while [disabled]. */
    fun openDialog(initial: GitHubWorkTab, cwd: String, disabled: Boolean = false): Boolean {
        if (cwd.isEmpty() || disabled) return false
        this.cwd = cwd
        tab = initial
        open = true
        fetch(initial)
        return true
    }

    /** :103-108 `selectTab`. */
    fun selectTab(next: GitHubWorkTab) {
        tab = next
        openMenu = null
        val state = stateOf(next)
        if (!state.loading && ((state.response == null && state.error.isEmpty()) || state.cwd != cwd)) fetch(next)
    }

    /** "Try again" (:246): the tab on show is read again. */
    fun retry() = fetch(tab)

    /** :65-94 `fetchTab`. */
    fun fetch(which: GitHubWorkTab) {
        val folder = cwd
        if (folder.isEmpty()) return
        cancelRead()
        openMenu = null
        copiedNumber = null
        copyErrorNumber = null
        val mine = ++seq
        loadingTab = which
        when (which) {
            GitHubWorkTab.Issues -> issues = GitHubTabState(loading = true, cwd = folder)
            GitHubWorkTab.PullRequests -> pullRequests = GitHubTabState(loading = true, cwd = folder)
        }
        val o = origin
        job = scope.launch {
            when (which) {
                GitHubWorkTab.Issues -> {
                    val r = guarded(o) { source.issues(it, folder) }
                    if (mine != seq) return@launch
                    issues = when (r) {
                        is SecurityResult.Ok -> GitHubTabState(response = r.value, cwd = folder)
                        else -> GitHubTabState(error = GitHubWorkCopy.failure(r, GitHubWorkCopy.ISSUES_FAILED, o), cwd = folder)
                    }
                }
                GitHubWorkTab.PullRequests -> {
                    val r = guarded(o) { source.pullRequests(it, folder) }
                    if (mine != seq) return@launch
                    pullRequests = when (r) {
                        is SecurityResult.Ok -> GitHubTabState(response = r.value, cwd = folder)
                        else -> GitHubTabState(error = GitHubWorkCopy.failure(r, GitHubWorkCopy.PULL_REQUESTS_FAILED, o), cwd = folder)
                    }
                }
            }
            loadingTab = null
        }
    }

    /** :276 a row's tap: its menu opens, or closes when it was open. */
    fun toggleMenu(number: Long) {
        openMenu = if (openMenu == number) null else number
    }

    /** :130-140 `workOnIssue`: the prompt and the folder for the composer, and the dialog closes. Null without a repository. */
    fun workOn(issue: GitHubIssue): GitHubWork? {
        val repository = issues.response?.repository ?: return null
        val work = GitHubWork(GitHubWorkPrompt.issue(repository, issue), cwd)
        close()
        return work
    }

    /** :136-140 `reviewPullRequest`. */
    fun review(pr: GitHubPullRequest): GitHubWork? {
        val repository = pullRequests.response?.repository ?: return null
        val work = GitHubWork(GitHubWorkPrompt.pullRequest(repository, pr), cwd)
        close()
        return work
    }

    /** :142-164: the prompt "Copy prompt" puts on the clipboard (null without a repository). */
    fun promptFor(issue: GitHubIssue): String? = issues.response?.repository?.let { GitHubWorkPrompt.issue(it, issue) }

    fun promptFor(pr: GitHubPullRequest): String? = pullRequests.response?.repository?.let { GitHubWorkPrompt.pullRequest(it, pr) }

    /** The clipboard's answer for row [number]: "Copied prompt", or "Copy failed — try again". */
    fun copied(number: Long, ok: Boolean) {
        copiedNumber = if (ok) number else null
        copyErrorNumber = if (ok) null else number
    }

    /** :124-127 `closeDialog` and the dialog's `onClose` (:199-202): the read in flight stops, the menu closes. */
    fun close() {
        cancelRead()
        openMenu = null
        open = false
    }

    /** The view left (another server, the sheet gone): nothing more lands. */
    fun dispose() {
        cancelRead()
        open = false
    }

    private fun stateOf(which: GitHubWorkTab): GitHubTabState<*> = when (which) {
        GitHubWorkTab.Issues -> issues
        GitHubWorkTab.PullRequests -> pullRequests
    }

    /** The read in flight stops; its tab goes back to empty (read again when next shown). */
    private fun cancelRead() {
        seq++
        job?.cancel()
        job = null
        when (loadingTab) {
            GitHubWorkTab.Issues -> if (issues.loading) issues = GitHubTabState()
            GitHubWorkTab.PullRequests -> if (pullRequests.loading) pullRequests = GitHubTabState()
            null -> Unit
        }
        loadingTab = null
    }

    /** No server: signed out. A call that throws is the web's `catch`: its fallback words (never a crash). */
    private suspend fun <T> guarded(o: String?, call: suspend (String) -> SecurityResult<T>): SecurityResult<T> {
        if (o == null) return SecurityResult.SignedOut()
        return try {
            call(o)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SecurityResult.Unavailable(null, o)
        }
    }
}
