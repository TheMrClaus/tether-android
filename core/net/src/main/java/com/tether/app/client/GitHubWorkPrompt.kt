package com.tether.app.client

/**
 * T8.4: lib/github-issue-prompt.mjs (tether 90fbb9f), byte for byte: the prompt "Work on this issue"
 * and "Review this PR" put in the composer, "Copy prompt" puts on the clipboard, and the attach
 * sheet attaches as `issue-N.md` / `pr-N.md`. Lengths and cuts are in UTF-16 code units, as
 * JavaScript's `length` / `slice` are; `trim` / `trimEnd` drop JavaScript's whitespace set ([jsSpace]),
 * not Kotlin's.
 */
object GitHubWorkPrompt {
    /** `MAX_GITHUB_ISSUE_BODY_CHARS`. */
    const val MAX_BODY_CHARS = 12_000

    private val LINE_ENDINGS = Regex("\r\n?")

    /** `truncateGitHubIssueBody`. */
    fun truncateBody(body: String?, maxChars: Int = MAX_BODY_CHARS): String {
        val normalized = jsTrim((body ?: "").replace(LINE_ENDINGS, "\n"))
        if (normalized.length <= maxChars) return normalized
        val prefix = normalized.substring(0, maxChars)
        val lastBreak = maxOf(prefix.lastIndexOf("\n\n"), prefix.lastIndexOf(" "))
        // `Math.floor(maxChars * 0.8)`.
        val cutAt = if (lastBreak >= Math.floor(maxChars * 0.8).toInt()) lastBreak else maxChars
        return jsTrimEnd(prefix.substring(0, cutAt)) + "\n\n[Issue body truncated.]"
    }

    /** `buildGitHubIssuePrompt`. */
    fun issue(repository: String, issue: GitHubIssue): String {
        val body = truncateBody(issue.body)
        return "Work on GitHub issue $repository#${issue.number}: ${jsTrim(issue.title)}\n" +
            "\n" +
            "$body\n" +
            "\n" +
            "Guidelines: implement only what the issue asks; follow the repo's existing conventions; run the project's gates/tests before finishing; don't touch unrelated files; commit with a subject referencing issue #${issue.number}. If the issue is already implemented in recent commits, verify instead of re-implementing."
    }

    /** `buildGitHubPullRequestPrompt`. */
    fun pullRequest(repository: String, pr: GitHubPullRequest): String {
        val body = truncateBody(pr.body)
        val head = jsTrim(pr.headRefName)
        val base = jsTrim(pr.baseRefName)
        val branches = if (head.isNotEmpty() && base.isNotEmpty()) " ($head → $base)" else ""
        return "Look into pull request $repository#${pr.number}: ${jsTrim(pr.title)}$branches\n" +
            "\n" +
            "$body\n" +
            "\n" +
            "Fetch the diff and review thread first: `gh pr diff --repo $repository ${pr.number}` and `gh pr view --repo $repository ${pr.number} --comments`. Review the change for correctness, adherence to the repo's existing conventions, and regressions. Decide whether the PR is complete or needs follow-up work: if something is missing, implement it; otherwise post a concrete review summary. If you're working in a checkout of the branch, run the project's gates/tests before finishing. Don't touch unrelated files; reference #${pr.number} in any commits."
    }

    /**
     * ECMAScript's WhiteSpace and LineTerminator (what `String.prototype.trim` drops): TAB, VT, FF,
     * SP, NBSP, ZWNBSP (U+FEFF), the Zs category (U+1680, U+2000-U+200A, U+202F, U+205F, U+3000),
     * LF, CR, U+2028 and U+2029. Not U+180E (no longer Zs) and not the C0 separators U+001C-U+001F,
     * which Kotlin's `isWhitespace` would drop.
     */
    fun jsSpace(c: Char): Boolean = when (c) {
        '\u0009', '\u000B', '\u000C', ' ', ' ', '﻿',
        '\n', '\r', ' ', ' ',
        ' ', ' ', ' ', '　' -> true
        else -> c in ' '..' '
    }

    fun jsTrim(text: String): String = text.trim(::jsSpace)

    fun jsTrimEnd(text: String): String = text.trimEnd(::jsSpace)
}
