package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * T8.4 wire shapes for the GitHub issues / pull requests reads, against tether 90fbb9f server.mjs
 * :8181-8211 (`GET /api/github/issues` and `/api/github/pull-requests`, `?cwd=`; 400 for a folder
 * resolveCwd refuses; 502 `{error}` when `gh` fails), lib/github-issues.mjs (normalizeIssues /
 * normalizePullRequests: the row shapes, a non-GitHub folder's `{repository: null, …: []}`) and
 * lib/protocol.ts :2080-2114, as github-work-dialog.tsx :65-94 sends them: a GET with
 * `accept: application/json` and the credential, only to the server the screen drew from, never
 * following a redirect.
 */
class GitHubWorkHttpTest {
    private val server = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var github: HttpGitHubWork

    private val json = "application/json; charset=utf-8"
    private val cwd = "/srv/ws/my repo&x=1"

    @Before fun setUp() {
        server.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        github = HttpGitHubWork(noRedirects, authority = { authority })
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private val origin get() = "http://${server.hostName}:${server.port}"

    private fun take(): RecordedRequest = server.takeRequest(20, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun nothingSent() = assertNull("nothing may be sent", server.takeRequest(200, TimeUnit.MILLISECONDS))

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", json).setBody(body)

    private fun assertSent(req: RecordedRequest, path: String) {
        assertEquals("GET", req.method)
        assertEquals(path, req.requestUrl!!.encodedPath)
        // encodeURIComponent's value, decoded by the server's URLSearchParams: the one parameter.
        assertEquals(listOf("cwd"), req.requestUrl!!.queryParameterNames.toList())
        assertEquals(cwd, req.requestUrl!!.queryParameter("cwd"))
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals(0L, req.bodySize)
    }

    @Test fun issuesReadTheListAsTheServerWritesIt() = runBlocking<Unit> {
        // github-issues.test.mjs: number, title, body, labels {name, color?}.
        server.enqueue(
            reply(
                200,
                """{"repository":"octo/repo","issues":[
                  {"number":60,"title":"Add GitHub issues","body":"Body\r\ntext","labels":[{"name":"enhancement","color":"a2eeef"},{"name":"ui"}]},
                  {"number":61,"title":"No labels","body":"","labels":[]}
                ]}""",
            ),
        )
        val list = (github.issues(origin, cwd) as SecurityResult.Ok).value
        assertEquals(
            GitHubIssuesList(
                "octo/repo",
                listOf(
                    GitHubIssue(60, "Add GitHub issues", "Body\r\ntext", listOf(GitHubLabel("enhancement", "a2eeef"), GitHubLabel("ui"))),
                    GitHubIssue(61, "No labels", "", emptyList()),
                ),
            ),
            list,
        )
        assertSent(take(), "/api/github/issues")
    }

    @Test fun pullRequestsReadEveryField() = runBlocking<Unit> {
        server.enqueue(
            reply(
                200,
                """{"repository":"octo/repo","pullRequests":[
                  {"number":76,"title":"PR tab","body":"b","labels":[{"name":"wip","color":"ededed"}],"headRefName":"feature/prs","baseRefName":"main","isDraft":true,"url":"https://github.com/octo/repo/pull/76","author":"octocat"},
                  {"number":77,"title":"Plain","body":"","labels":[],"headRefName":"","baseRefName":"","isDraft":false,"url":"","author":""}
                ]}""",
            ),
        )
        val list = (github.pullRequests(origin, cwd) as SecurityResult.Ok).value
        assertEquals(
            GitHubPullRequestsList(
                "octo/repo",
                listOf(
                    GitHubPullRequest(76, "PR tab", "b", listOf(GitHubLabel("wip", "ededed")), "feature/prs", "main", true, "https://github.com/octo/repo/pull/76", "octocat"),
                    GitHubPullRequest(77, "Plain"),
                ),
            ),
            list,
        )
        assertSent(take(), "/api/github/pull-requests")
    }

    @Test fun aFolderThatIsNotAGitHubRepositoryIsANullRepository() = runBlocking<Unit> {
        // github-issues.mjs: `if (!repository) return { repository: null, issues: [] }`.
        server.enqueue(reply(200, """{"repository":null,"issues":[]}"""))
        assertEquals(GitHubIssuesList(null, emptyList()), (github.issues(origin, cwd) as SecurityResult.Ok).value)
        server.enqueue(reply(200, """{"repository":null,"pullRequests":[]}"""))
        assertEquals(GitHubPullRequestsList(null, emptyList()), (github.pullRequests(origin, cwd) as SecurityResult.Ok).value)
    }

    @Test fun rowsTheServerWouldNotWriteAreDropped() = runBlocking<Unit> {
        // normalizeIssues: a positive safe-integer number and a string title; a label needs a non-blank name.
        server.enqueue(
            reply(
                200,
                """{"repository":"o/r","issues":[
                  {"number":0,"title":"zero"},{"number":-1,"title":"neg"},{"number":"5","title":"string number"},
                  {"number":9007199254740992,"title":"unsafe"},{"number":5},{"number":6,"title":7},"not an object",
                  {"number":9007199254740991,"title":"kept","labels":[{"name":"  "},{"name":3},{"color":"fff"},"x",{"name":"ok","color":5}]}
                ]}""",
            ),
        )
        assertEquals(
            GitHubIssuesList("o/r", listOf(GitHubIssue(9007199254740991, "kept", "", listOf(GitHubLabel("ok"))))),
            (github.issues(origin, cwd) as SecurityResult.Ok).value,
        )
    }

    @Test fun theFoldersRefusalIsTheServersSentence() = runBlocking<Unit> {
        server.enqueue(reply(400, """{"error":"That working folder is not available."}"""))
        assertEquals(SecurityResult.Refused(400, "That working folder is not available.", origin), github.issues(origin, cwd))
    }

    @Test fun aGhFailureIsTheServersSentence() = runBlocking<Unit> {
        server.enqueue(reply(502, """{"error":"GitHub issues could not be loaded. Check the server's gh authentication."}"""))
        assertEquals(SecurityResult.Refused(502, "GitHub issues could not be loaded. Check the server's gh authentication.", origin), github.issues(origin, cwd))
        server.enqueue(reply(502, """{"error":"GitHub pull requests could not be loaded. Check the server's gh authentication."}"""))
        assertEquals(SecurityResult.Refused(502, "GitHub pull requests could not be loaded. Check the server's gh authentication.", origin), github.pullRequests(origin, cwd))
        // A JSON refusal without a sentence: the caller's fallback (the web's).
        server.enqueue(reply(500, """{}"""))
        assertEquals(SecurityResult.Refused(500, "", origin), github.issues(origin, cwd))
    }

    @Test fun answersThatAreNotTheRoutesAreUnavailable() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Content-Type", "text/plain").setBody("oops"))
        assertEquals(SecurityResult.Unavailable(500, origin), github.issues(origin, cwd))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("ok"))
        assertEquals(SecurityResult.Unavailable(200, origin), github.issues(origin, cwd))
        server.enqueue(reply(200, """[1,2]"""))
        assertEquals(SecurityResult.Unavailable(200, origin), github.pullRequests(origin, cwd))
    }

    @Test fun theApiGates401IsSignedOut() = runBlocking<Unit> {
        server.enqueue(reply(401, """{"error":"Authentication required."}"""))
        assertEquals(SecurityResult.SignedOut(origin), github.issues(origin, cwd))
    }

    @Test fun aRedirectIsNeverFollowed() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://sso.example.invalid/login"))
        assertEquals(SecurityResult.Blocked(302, origin), github.issues(origin, cwd))
        take()
        nothingSent()
    }

    @Test fun anotherServerOrNoneSendsNothing() = runBlocking<Unit> {
        assertEquals(SecurityResult.NotSent(origin), github.issues("https://other.example.invalid", cwd))
        authority = FilesAuthority.SignedOut
        assertEquals(SecurityResult.SignedOut(), github.pullRequests(origin, cwd))
        authority = FilesAuthority.LocalNetworkBlocked
        assertEquals(SecurityResult.LocalNetworkBlocked, github.issues(origin, cwd))
        nothingSent()
    }

    @Test fun noAnswerIsUnreachable() = runBlocking<Unit> {
        val at = origin
        server.shutdown()
        assertEquals(SecurityResult.Unavailable(null, at), github.issues(at, cwd))
    }

    @Test fun theDefaultSourceSendsNothing() = runBlocking<Unit> {
        assertEquals(SecurityResult.SignedOut(), GitHubWorkSource.Unavailable.issues(origin, cwd))
        assertEquals(SecurityResult.SignedOut(), GitHubWorkSource.Unavailable.pullRequests(origin, cwd))
        nothingSent()
    }
}
