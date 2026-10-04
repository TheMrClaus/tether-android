package com.tether.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tether.app.client.GitHubIssue
import com.tether.app.client.GitHubPullRequest
import com.tether.app.client.GitHubWorkPrompt
import com.tether.app.client.GitHubWorkSource
import com.tether.app.client.SecurityResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/*
 * T8.4: the attach sheet's "Add issue or PR" (components/attach-sheet.tsx 90fbb9f :189-330) and what
 * a pick attaches (components/chat-view.tsx :2688-2724 `attachGitHubWork`): the session folder's open
 * issues and pull requests in one searchable list; a pick attaches the issue's or PR's prompt (the
 * new-session dialog's builders, byte for byte) as a markdown file `issue-N.md` / `pr-N.md` beside
 * whatever is typed, staged like a picked file (the same count and size limits and their words).
 */

/** attach-sheet.tsx `GitHubWorkSelection`. */
@Immutable
sealed interface GitHubWorkSelection {
    val number: Long

    data class Issue(val issue: GitHubIssue) : GitHubWorkSelection {
        override val number: Long get() = issue.number
    }

    data class PullRequest(val pr: GitHubPullRequest) : GitHubWorkSelection {
        override val number: Long get() = pr.number
    }
}

/** What the chat composer lends the sheet: the reads, for the server signed in to ([origin], null: none). */
@Immutable
class ComposerGitHub(val source: GitHubWorkSource, val origin: String?)

/**
 * chat-view.tsx :2695-2723: the attachment a pick makes, as a staged source: the prompt's UTF-8 bytes,
 * named `${kind}-${number}.md`, `text/markdown`. Staged through the composer's intake like any pick.
 */
class GitHubWorkAttachmentSource(repository: String, selection: GitHubWorkSelection) : AttachmentSource {
    val text: String = when (selection) {
        is GitHubWorkSelection.Issue -> GitHubWorkPrompt.issue(repository, selection.issue)
        is GitHubWorkSelection.PullRequest -> GitHubWorkPrompt.pullRequest(repository, selection.pr)
    }
    private val bytes = text.toByteArray(Charsets.UTF_8)

    override val displayName: String = "${if (selection is GitHubWorkSelection.Issue) "issue" else "pr"}-${selection.number}.md"
    override val reportedSize: Long = bytes.size.toLong()
    override val declaredType: String = "text/markdown"

    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** attach-sheet.tsx `matchesQuery` (:96-100). */
internal fun matchesGitHubQuery(query: String, number: Long, title: String): Boolean {
    val needle = GitHubWorkPrompt.jsTrim(query).lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    return number.toString().contains(needle) || title.lowercase(Locale.ROOT).contains(needle)
}

/**
 * attach-sheet.tsx :115-232's GitHub state for one session's composer (one server, [origin]): both
 * lists read together for the folder, kept for that folder (re-opening does not read again; after an
 * error it does), and the words for a failed read (the web's one sentence, whatever the server said;
 * natively, what it has no words for: signed out, another server, a sign-in page).
 */
@Stable
class AttachGitHubController(
    private val source: GitHubWorkSource,
    val origin: String?,
    private val scope: CoroutineScope,
) {
    var loading: Boolean by mutableStateOf(false)
        private set
    var error: String by mutableStateOf("")
        private set
    var repository: String? by mutableStateOf(null)
        private set
    var issues: List<GitHubIssue> by mutableStateOf(emptyList())
        private set
    var pullRequests: List<GitHubPullRequest> by mutableStateOf(emptyList())
        private set

    private var loadedForCwd: String? = null
    private var job: Job? = null

    /** The goldens' seam. */
    fun seed(repository: String?, issues: List<GitHubIssue>, pullRequests: List<GitHubPullRequest>, loading: Boolean = false, error: String = "") {
        this.repository = repository
        this.issues = issues
        this.pullRequests = pullRequests
        this.loading = loading
        this.error = error
    }

    /** :189-232 `openGitHub`: read both lists for [cwd], unless they are already here for it. */
    fun open(cwd: String) {
        if (loadedForCwd == cwd && error.isEmpty()) return
        if (cwd.isEmpty()) {
            error = GitHubWorkCopy.NO_WORKSPACE
            return
        }
        job?.cancel()
        loading = true
        error = ""
        val o = origin
        job = scope.launch {
            if (o == null) {
                error = GitHubWorkCopy.SIGNED_OUT
                loading = false
                return@launch
            }
            val issuesRead = async { guarded(o) { source.issues(o, cwd) } }
            val pullsRead = async { guarded(o) { source.pullRequests(o, cwd) } }
            val i = issuesRead.await()
            val p = pullsRead.await()
            if (i is SecurityResult.Ok && p is SecurityResult.Ok) {
                repository = i.value.repository ?: p.value.repository
                issues = i.value.issues
                pullRequests = p.value.pullRequests
                loadedForCwd = cwd
            } else {
                error = failure(if (i !is SecurityResult.Ok) i else p, o)
            }
            loading = false
        }
    }

    /** The sheet closed (:162-171 `close` aborts): the read stops; it is read again on the next open. */
    fun cancel() {
        job?.cancel()
        job = null
        loading = false
    }

    /** :219-221: any refusal is the web's one sentence; native words only where the web has none. */
    private fun failure(r: SecurityResult<*>, o: String): String = when (r) {
        is SecurityResult.Refused, is SecurityResult.Unavailable, is SecurityResult.OwnerSignInNeeded -> GitHubWorkCopy.BOTH_FAILED
        else -> GitHubWorkCopy.failure(r, GitHubWorkCopy.BOTH_FAILED, o)
    }

    private suspend fun <T> guarded(o: String, call: suspend () -> SecurityResult<T>): SecurityResult<T> = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SecurityResult.Unavailable(null, o)
    }
}
