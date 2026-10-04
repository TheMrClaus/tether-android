package com.tether.app.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

// ─────────────────────────────────────────────────────────────────────────────
// T8.4: the workspace's open GitHub issues and pull requests, as the web's GitHubWorkDialog
// (components/github-work-dialog.tsx 90fbb9f :65-94) and the attach sheet's "Add issue or PR"
// (components/attach-sheet.tsx :189-232) read them. The routes (server.mjs 90fbb9f :8181-8211,
// lib/github-issues.mjs), both open to any signed-in principal:
//   GET /api/github/issues?cwd=<folder>          200 { repository: "owner/repo" | null, issues: GitHubIssue[] }
//   GET /api/github/pull-requests?cwd=<folder>   200 { repository: "owner/repo" | null, pullRequests: GitHubPullRequest[] }
//   400 { error: "That working folder is not available." }   (resolveCwd refused the folder)
//   502 { error: "GitHub issues could not be loaded. Check the server's gh authentication." } (and its siblings)
// The shapes are lib/protocol.ts :2080-2114. A folder that is not a GitHub repository is a 200 with a
// null repository and an empty list (the web's "No GitHub repo detected for this workspace.").
//
// Every call goes through [FixedRouteHttp]: redirects off, the fixed path on the paired origin with
// the one `cwd` parameter (encoded as the web's `encodeURIComponent`), and only when that origin is
// the one the screen drew from (else [SecurityResult.NotSent], nothing sent). Nothing here logs.
// ─────────────────────────────────────────────────────────────────────────────

/** lib/protocol.ts `GitHubIssueLabel`. */
data class GitHubLabel(val name: String, val color: String? = null)

/** lib/protocol.ts `GitHubIssue`. [body] is kept whole: the prompt builders cut it, as on the web. */
data class GitHubIssue(
    val number: Long,
    val title: String,
    val body: String = "",
    val labels: List<GitHubLabel> = emptyList(),
)

/** lib/protocol.ts `GitHubPullRequest`. */
data class GitHubPullRequest(
    val number: Long,
    val title: String,
    val body: String = "",
    val labels: List<GitHubLabel> = emptyList(),
    val headRefName: String = "",
    val baseRefName: String = "",
    val isDraft: Boolean = false,
    val url: String = "",
    val author: String = "",
)

/** lib/protocol.ts `GitHubIssuesResponse`. */
data class GitHubIssuesList(val repository: String?, val issues: List<GitHubIssue>)

/** lib/protocol.ts `GitHubPullRequestsResponse`. */
data class GitHubPullRequestsList(val repository: String?, val pullRequests: List<GitHubPullRequest>)

/** The two reads, each for the server [origin] the screen drew from and the working folder [cwd]. */
interface GitHubWorkSource {
    suspend fun issues(origin: String, cwd: String): SecurityResult<GitHubIssuesList>
    suspend fun pullRequests(origin: String, cwd: String): SecurityResult<GitHubPullRequestsList>

    /** No client (previews, fakes): nothing is ever sent. */
    object Unavailable : GitHubWorkSource {
        override suspend fun issues(origin: String, cwd: String): SecurityResult<GitHubIssuesList> = SecurityResult.SignedOut()
        override suspend fun pullRequests(origin: String, cwd: String): SecurityResult<GitHubPullRequestsList> = SecurityResult.SignedOut()
    }

    companion object {
        const val ISSUES_PATH = "/api/github/issues"
        const val PULL_REQUESTS_PATH = "/api/github/pull-requests"
        const val CWD = "cwd"

        /**
         * The server lists at most 50 of each with their bodies, from a `gh` child bounded at 4 MiB of
         * output (lib/github-issues.mjs COMMAND_MAX_BUFFER); twice that leaves room for re-encoding.
         */
        const val MAX_BODY_BYTES: Long = 8L * 1024L * 1024L

        /** `git remote get-url` then `gh … list`, each bounded at 15 s on the server (COMMAND_TIMEOUT_MS). */
        const val CALL_TIMEOUT_MS: Long = 45_000L
    }
}

/** [GitHubWorkSource] over [FixedRouteHttp], with the client's per-call (server, credential) read. */
class HttpGitHubWork(
    http: OkHttpClient,
    private val authority: () -> FilesAuthority,
    maxBytes: Long = GitHubWorkSource.MAX_BODY_BYTES,
    callTimeoutMs: Long = GitHubWorkSource.CALL_TIMEOUT_MS,
) : GitHubWorkSource {
    private val route = FixedRouteHttp(http, maxBytes, callTimeoutMs)

    // github-work-dialog.tsx :78 `fetch(`${path}?cwd=${encodeURIComponent(cwd)}`, { accept: json })`.
    override suspend fun issues(origin: String, cwd: String): SecurityResult<GitHubIssuesList> =
        call(origin, GitHubWorkSource.ISSUES_PATH, cwd, GitHubWorkJson::issues)

    override suspend fun pullRequests(origin: String, cwd: String): SecurityResult<GitHubPullRequestsList> =
        call(origin, GitHubWorkSource.PULL_REQUESTS_PATH, cwd, GitHubWorkJson::pullRequests)

    private suspend fun <T> call(origin: String, path: String, cwd: String, parse: (JsonObject) -> T): SecurityResult<T> =
        when (val out = route.call(authority(), origin, FixedRouteHttp.Method.GET, path, query = GitHubWorkSource.CWD to cwd)) {
            FixedRouteHttp.Outcome.SignedOut -> SecurityResult.SignedOut()
            FixedRouteHttp.Outcome.LocalNetworkBlocked -> SecurityResult.LocalNetworkBlocked
            is FixedRouteHttp.Outcome.OtherOrigin -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.NotBuilt -> SecurityResult.NotSent(out.origin)
            is FixedRouteHttp.Outcome.Blocked -> SecurityResult.Blocked(out.code, out.origin)
            is FixedRouteHttp.Outcome.Unreachable -> SecurityResult.Unavailable(null, out.origin)
            is FixedRouteHttp.Outcome.Answered -> answered(out, parse)
        }

    /**
     * Tether's answer, read as the web reads it (:84-90): a 2xx is the list (a body that is not a JSON
     * object is [SecurityResult.Unavailable], the caller's fallback words); its own 401 is signed out;
     * any other status with a JSON object is [SecurityResult.Refused] with its `error` (the 400's and
     * 502's sentences), empty when it has none (the web's fallback); a non-JSON refusal is unavailable.
     */
    private fun <T> answered(out: FixedRouteHttp.Outcome.Answered, parse: (JsonObject) -> T): SecurityResult<T> {
        val json = out.json
        return when {
            out.code in 200..299 -> json?.let(parse)?.let { SecurityResult.Ok(it, out.origin, null) } ?: SecurityResult.Unavailable(out.code, out.origin)
            out.code == 401 && out.jsonType -> SecurityResult.SignedOut(out.origin)
            json != null -> SecurityResult.Refused(out.code, json.let(DeviceSecurityJson::errorSentence).orEmpty(), out.origin)
            else -> SecurityResult.Unavailable(out.code, out.origin)
        }
    }
}

/**
 * The tolerant read of the lists, as lib/github-issues.mjs `normalizeIssues` / `normalizePullRequests`
 * write them: a row without a positive safe-integer `number` or a string `title` is dropped; a label
 * without a non-blank string `name` is dropped; a missing string field is "" (a missing flag false).
 * The text is kept as sent (the prompts must be the web's byte for byte); screens draw it by the label rule.
 */
object GitHubWorkJson {
    /** JavaScript's `Number.MAX_SAFE_INTEGER`. */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun issues(o: JsonObject): GitHubIssuesList = GitHubIssuesList(
        repository = repository(o["repository"]),
        issues = rows(o["issues"]) { row ->
            val number = number(row["number"]) ?: return@rows null
            val title = string(row["title"]) ?: return@rows null
            GitHubIssue(number, title, string(row["body"]).orEmpty(), labels(row["labels"]))
        },
    )

    fun pullRequests(o: JsonObject): GitHubPullRequestsList = GitHubPullRequestsList(
        repository = repository(o["repository"]),
        pullRequests = rows(o["pullRequests"]) { row ->
            val number = number(row["number"]) ?: return@rows null
            val title = string(row["title"]) ?: return@rows null
            GitHubPullRequest(
                number = number,
                title = title,
                body = string(row["body"]).orEmpty(),
                labels = labels(row["labels"]),
                headRefName = string(row["headRefName"]).orEmpty(),
                baseRefName = string(row["baseRefName"]).orEmpty(),
                isDraft = bool(row["isDraft"]),
                url = string(row["url"]).orEmpty(),
                author = string(row["author"]).orEmpty(),
            )
        },
    )

    /** `"owner/repo"`, or null (not a GitHub repository; the web's falsy `repository`). */
    private fun repository(element: JsonElement?): String? = string(element)?.takeIf { it.isNotEmpty() }

    private fun <T> rows(element: JsonElement?, read: (JsonObject) -> T?): List<T> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(read) }

    private fun labels(element: JsonElement?): List<GitHubLabel> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            val label = item as? JsonObject ?: return@mapNotNull null
            val name = string(label["name"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GitHubLabel(name, string(label["color"]))
        }

    private fun number(element: JsonElement?): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        val n = p.content.toLongOrNull() ?: return null
        return n.takeIf { it in 1..MAX_SAFE_INTEGER }
    }

    private fun string(element: JsonElement?): String? {
        val p = element as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    private fun bool(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive ?: return false
        return !p.isString && p.content == "true"
    }
}
