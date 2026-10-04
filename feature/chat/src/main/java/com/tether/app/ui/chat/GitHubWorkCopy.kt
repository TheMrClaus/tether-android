package com.tether.app.ui.chat

import com.tether.app.client.LabelText
import com.tether.app.client.SecurityResult
import java.util.regex.Pattern

/**
 * T8.4: the GitHub issues / pull requests words (components/github-work-dialog.tsx and
 * components/attach-sheet.tsx, tether 90fbb9f), the web's own, and how a failed read is said.
 */
object GitHubWorkCopy {
    // github-work-dialog.tsx :171-193, the two composer buttons (aria-label, title).
    const val ISSUES_BUTTON = "GitHub issues"
    const val PULL_REQUESTS_BUTTON = "Pull requests"
    const val ISSUES_BUTTON_HINT = "Open issues for this workspace's GitHub repository"
    const val PULL_REQUESTS_BUTTON_HINT = "Open pull requests for this workspace's GitHub repository"

    // :195-245, the dialog.
    const val DIALOG_TITLE = "GitHub"
    const val TABS_LABEL = "GitHub sections"
    const val TAB_ISSUES = "Issues"
    const val TAB_PULL_REQUESTS = "Pull requests"
    const val PANEL_ISSUES = "Open GitHub issues"
    const val PANEL_PULL_REQUESTS = "Open GitHub pull requests"
    const val LOADING_ISSUES = "Loading open issues…"
    const val LOADING_PULL_REQUESTS = "Loading open pull requests…"
    const val TRY_AGAIN = "Try again"
    const val SET_UP = "Set up GitHub connection"
    const val NO_REPO = "No GitHub repo detected for this workspace."
    fun noOpenIssues(repository: String) = "No open issues in $repository."
    fun noOpenPullRequests(repository: String) = "No open pull requests in $repository."
    const val NO_LABELS = "No labels"
    const val UNKNOWN_BRANCH = "unknown branch"
    const val WORK_ON_ISSUE = "Work on this issue"
    const val REVIEW_PR = "Review this PR"
    const val VIEW_ON_GITHUB = "View on GitHub"
    const val COPY_PROMPT = "Copy prompt"
    const val COPIED_PROMPT = "Copied prompt"
    const val COPY_FAILED = "Copy failed — try again"
    const val CLOSE = "Close"
    fun issueRow(number: Long) = "Actions for issue #$number"
    fun pullRequestRow(number: Long) = "Actions for pull request #$number"
    fun issueActions(number: Long) = "Issue #$number actions"
    fun pullRequestActions(number: Long) = "Pull request #$number actions"

    /** :84-93, the fallbacks when the server says nothing. */
    const val ISSUES_FAILED = "GitHub issues could not be loaded."
    const val PULL_REQUESTS_FAILED = "GitHub pull requests could not be loaded."

    // attach-sheet.tsx :189-330, the attach sheet's GitHub view.
    const val ATTACH_ROW = "Add issue or PR"
    const val ATTACH_TITLE = "Attach issue or PR"
    const val ATTACH_BACK = "Back to attachment menu"
    const val SEARCH_PLACEHOLDER = "Search issues and PRs…"
    const val SEARCH_LABEL = "Search issues and pull requests"
    const val LOADING_BOTH = "Loading open issues and pull requests…"
    const val BOTH_FAILED = "GitHub issues and pull requests could not be loaded."
    const val NO_WORKSPACE = "No workspace directory for this session."
    const val NO_MATCHES = "No open issues or pull requests match your search."
    const val NONE_OPEN = "No open issues or pull requests."

    // Native: what the web has no words for (no credential, the phone's local-network block, a sign-in
    // gateway in front of Tether, another server signed in to).
    const val SIGNED_OUT = "Signed out — sign in again to load GitHub issues and pull requests."
    const val LOCAL_NETWORK = "Local network access is blocked"
    const val NOT_SENT_OTHER = "Nothing was sent: the app is now signed in to another server."
    const val NOT_SENT = "Nothing was sent."
    fun blocked(code: Int) = "A sign-in page answered instead of Tether (HTTP $code). Exempt /api/github/ for paired devices."

    /** github-work-dialog.tsx :251 `/gh authentication/i` (ASCII case folding only, as JavaScript's `i` without `u`). */
    private val GH_AUTH: Pattern = Pattern.compile("gh authentication", Pattern.CASE_INSENSITIVE)

    /** Whether [error] names gh authentication as the cause (the dialog then offers [SET_UP]). */
    fun needsSetup(error: String): Boolean = GH_AUTH.matcher(error).find()

    /**
     * The words for a read that did not return the list: Tether's own sentence (the 400's, the 502's)
     * by the label rule, else [fallback] (the web's `typeof body.error === "string" ? body.error : fallback`).
     * [origin] is the server the read was for.
     */
    fun failure(r: SecurityResult<*>, fallback: String, origin: String?): String = when (r) {
        is SecurityResult.Ok -> fallback
        is SecurityResult.Refused -> LabelText.error(r.message).ifEmpty { fallback }
        is SecurityResult.SignedOut -> SIGNED_OUT
        SecurityResult.LocalNetworkBlocked -> LOCAL_NETWORK
        is SecurityResult.Blocked -> blocked(r.code)
        is SecurityResult.NotSent -> if (r.origin != null && r.origin != origin) NOT_SENT_OTHER else NOT_SENT
        is SecurityResult.OwnerSignInNeeded, is SecurityResult.Unavailable -> fallback
    }
}
