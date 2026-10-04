package com.tether.app.client

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T8.4: [GitHubWorkPrompt] against tether 90fbb9f lib/github-issue-prompt.mjs, byte for byte. Every
 * expected value below was produced by running that module (node 22) on the same input; a prompt
 * over 3000 code units is compared by its length and the SHA-256 of its UTF-16LE code units (lone
 * surrogates included, which the surrogate case leaves where JavaScript's `slice` cuts a pair).
 * Covered: CRLF / CR normalising, JavaScript's trim set (U+3000, NBSP, U+FEFF, U+2028/9, U+1680,
 * U+205F, U+202F, U+200A trimmed; U+180E and U+001C kept), the 12 000 cut at the last paragraph or
 * space past 80 %, the hard cut, a cut inside a surrogate pair, the exact limit, and the PR's
 * `(head → base)` only when both are non-blank.
 */
class GitHubWorkPromptTest {
    private fun assertSameUtf16(length: Int, sha256: String, actual: String) {
        assertEquals(length, actual.length)
        val bytes = ByteArray(actual.length * 2)
        actual.forEachIndexed { i, c ->
            bytes[i * 2] = (c.code and 0xFF).toByte()
            bytes[i * 2 + 1] = (c.code shr 8).toByte()
        }
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(sha256, hex)
    }

    @Test fun issueSimple() = assertEquals(
        "Work on GitHub issue octo/repo#60: Add GitHub issues button\n\nLine one\nLine two\nLine three\n\nGuidelines: implement only what the issue asks; follow the repo's existing conventions; run the project's gates/tests before finishing; don't touch unrelated files; commit with a subject referencing issue #60. If the issue is already implemented in recent commits, verify instead of re-implementing.",
        GitHubWorkPrompt.issue("octo/repo", GitHubIssue(60, "  Add GitHub issues button \n", "Line one\r\nLine two\rLine three\n\n")),
    )

    @Test fun issueEmptyBody() = assertEquals(
        "Work on GitHub issue octo/repo#1: No body\n\n\n\nGuidelines: implement only what the issue asks; follow the repo's existing conventions; run the project's gates/tests before finishing; don't touch unrelated files; commit with a subject referencing issue #1. If the issue is already implemented in recent commits, verify instead of re-implementing.",
        GitHubWorkPrompt.issue("octo/repo", GitHubIssue(1, "No body", "")),
    )

    @Test fun issueJsWhitespace() = assertEquals(
        "Work on GitHub issue a.b/c-d_e#9007199254740991: Title\n\ntext \u180E\u001C\n\nGuidelines: implement only what the issue asks; follow the repo's existing conventions; run the project's gates/tests before finishing; don't touch unrelated files; commit with a subject referencing issue #9007199254740991. If the issue is already implemented in recent commits, verify instead of re-implementing.",
        GitHubWorkPrompt.issue("a.b/c-d_e", GitHubIssue(9007199254740991, "\u3000Title\u00A0\uFEFF\u2028", "\u2029\u1680 text \u180E\u001C \u205F\u202F\u200A")),
    )

    @Test fun issueLongWords() = assertSameUtf16(12364, "be9934538e8e383a3a87eed3725df49ed1ea5352dc793f7d6e2869d2473db4c0", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(7, "Long", "word ".repeat(3000))))

    @Test fun issueLongNoBreak() = assertSameUtf16(12369, "cfa1308ec6d30b77ffae8428404a220c7775c32f864ad0822013455b3bb6b135", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(8, "Hard cut", "x".repeat(13000))))

    @Test fun issueEarlyBreak() = assertSameUtf16(12372, "24aed03726f23caa46a2be3313d920346f15d7c745d2e09db8f2815e91e6e1c9", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(9, "Early break", "y".repeat(5000) + " " + "z".repeat(9000))))

    @Test fun issueParagraphCut() = assertSameUtf16(11372, "af11fab9c305593ada8651e57ed241ab2010592109bb6f5514e9888467c9eea8", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(10, "Paragraph", "p".repeat(11000) + "   \n\n" + "q".repeat(2000))))

    @Test fun issueSurrogateCut() = assertSameUtf16(12368, "5ca47cfbe172eee07d41b1e813a6cb2850e15d1502306cdc10b46d934ab39278", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(11, "Emoji", "a" + "\uD83D\uDE00".repeat(7000))))

    @Test fun issueExactlyMax() = assertSameUtf16(12343, "b613af6376238bd284b5430c728f0d9df033a83cad3f22468d3a29a4a61403ae", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(12, "Exact", "e".repeat(12000))))

    @Test fun issueCrlfLong() = assertSameUtf16(12366, "f11f5d0ec71a0519c498288d5858e51f4dcada820d7a42463e831daced8d3988", GitHubWorkPrompt.issue("octo/repo", GitHubIssue(13, "CRLF", "line\r\n".repeat(2500))))

    @Test fun prBranches() = assertEquals(
        "Look into pull request octo/repo#76: Pull requests tab (feature/prs \u2192 main)\n\nAdds the tab.\n\nFetch the diff and review thread first: `gh pr diff --repo octo/repo 76` and `gh pr view --repo octo/repo 76 --comments`. Review the change for correctness, adherence to the repo's existing conventions, and regressions. Decide whether the PR is complete or needs follow-up work: if something is missing, implement it; otherwise post a concrete review summary. If you're working in a checkout of the branch, run the project's gates/tests before finishing. Don't touch unrelated files; reference #76 in any commits.",
        GitHubWorkPrompt.pullRequest("octo/repo", GitHubPullRequest(76, "Pull requests tab ", "Adds the tab.\r\n", headRefName = " feature/prs ", baseRefName = "main")),
    )

    @Test fun prNoBase() = assertEquals(
        "Look into pull request octo/repo#77: No base\n\n\n\nFetch the diff and review thread first: `gh pr diff --repo octo/repo 77` and `gh pr view --repo octo/repo 77 --comments`. Review the change for correctness, adherence to the repo's existing conventions, and regressions. Decide whether the PR is complete or needs follow-up work: if something is missing, implement it; otherwise post a concrete review summary. If you're working in a checkout of the branch, run the project's gates/tests before finishing. Don't touch unrelated files; reference #77 in any commits.",
        GitHubWorkPrompt.pullRequest("octo/repo", GitHubPullRequest(77, "No base", "", headRefName = "topic", baseRefName = "")),
    )

    @Test fun prBlankHead() = assertEquals(
        "Look into pull request octo/repo#78: Draft\n\nWIP\n\nFetch the diff and review thread first: `gh pr diff --repo octo/repo 78` and `gh pr view --repo octo/repo 78 --comments`. Review the change for correctness, adherence to the repo's existing conventions, and regressions. Decide whether the PR is complete or needs follow-up work: if something is missing, implement it; otherwise post a concrete review summary. If you're working in a checkout of the branch, run the project's gates/tests before finishing. Don't touch unrelated files; reference #78 in any commits.",
        GitHubWorkPrompt.pullRequest("octo/repo", GitHubPullRequest(78, "Draft", "WIP", headRefName = " \u00A0", baseRefName = "main", isDraft = true)),
    )

    @Test fun prLong() = assertSameUtf16(12591, "6944a2783ebb71cd3645a5bc567fbcc8bbcfc9fb58f3696bab4397ff984c4e9e", GitHubWorkPrompt.pullRequest("octo/repo", GitHubPullRequest(79, "Long PR", "review ".repeat(2000), headRefName = "a", baseRefName = "b")))

    @Test fun truncateNull() = assertEquals("", GitHubWorkPrompt.truncateBody(null, 12000))

    @Test fun truncateSmallMax() = assertEquals("aaaa bbbb\n\n[Issue body truncated.]", GitHubWorkPrompt.truncateBody("aaaa bbbb cccc dddd", 10))

    @Test fun truncateSmallMaxNoBreak() = assertEquals("aaaaaaaaaa\n\n[Issue body truncated.]", GitHubWorkPrompt.truncateBody("aaaaaaaaaaaaaaa", 10))

    @Test fun truncateBreakAtEightyPercent() = assertEquals("aaaaaaa bb\n\n[Issue body truncated.]", GitHubWorkPrompt.truncateBody("aaaaaaa bbbbbbbbb", 10))

    @Test fun truncateBreakExactlyEightyPercent() = assertEquals("aaaaaaaa\n\n[Issue body truncated.]", GitHubWorkPrompt.truncateBody("aaaaaaaa bbbbbbb", 10))

    @Test fun jsTrimKeepsWhatKotlinWouldDrop() {
        // Kotlin's trim() drops U+001C-U+001F; JavaScript's keeps them. JavaScript drops U+FEFF; Kotlin keeps it.
        assertEquals("\u001Cx\u001F", GitHubWorkPrompt.jsTrim("\uFEFF\u001Cx\u001F\u3000"))
    }
}
