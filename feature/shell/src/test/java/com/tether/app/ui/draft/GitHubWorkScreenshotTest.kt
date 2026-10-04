package com.tether.app.ui.draft

import androidx.compose.ui.test.junit4.createComposeRule
import com.tether.app.client.GitHubIssuesList
import com.tether.app.client.GitHubPullRequestsList
import com.tether.app.ui.theme.TetherSkin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.4: the GitHub dialog (components/github-work-dialog.tsx 90fbb9f) over the new-session sheet.
 * `loading` = the Issues tab's first read; `auth-error` = the server's gh-authentication sentence with
 * Try again and Set up GitHub connection; `no-repo` = a folder that is not a GitHub repository;
 * `empty` = the Pull requests tab of a repository with none open; `issues` = the list with #60's menu
 * open and its prompt copied; `pulls` = the pull requests (a draft, head → base, an unknown branch).
 * Seeded synchronously; the source throws on any read (a golden that reads anything fails).
 */
enum class GitHubShot(val id: String) {
    Loading("github-work-loading"),
    AuthError("github-work-auth-error"),
    NoRepo("github-work-no-repo"),
    Empty("github-work-empty"),
    Issues("github-work-issues"),
    Pulls("github-work-pulls"),
}

private object NoReads : com.tether.app.client.GitHubWorkSource {
    override suspend fun issues(origin: String, cwd: String) = throw AssertionError("a golden must not read issues")
    override suspend fun pullRequests(origin: String, cwd: String) = throw AssertionError("a golden must not read pull requests")
}

private fun seeded(shot: GitHubShot): GitHubWorkController {
    val c = GitHubWorkController(NoReads, DraftFixtures.ORIGIN, CoroutineScope(Dispatchers.Unconfined + Job()))
    val cwd = DraftFixtures.ROOT
    when (shot) {
        GitHubShot.Loading -> c.seed(GitHubWorkTab.Issues, cwd, issues = GitHubTabState(loading = true, cwd = cwd))
        GitHubShot.AuthError -> c.seed(GitHubWorkTab.Issues, cwd, issues = GitHubTabState(error = GitHubFixtures.AUTH_ERROR, cwd = cwd))
        GitHubShot.NoRepo -> c.seed(GitHubWorkTab.Issues, cwd, issues = GitHubTabState(response = GitHubIssuesList(null, emptyList()), cwd = cwd))
        GitHubShot.Empty -> c.seed(GitHubWorkTab.PullRequests, cwd, pullRequests = GitHubTabState(response = GitHubPullRequestsList(GitHubFixtures.REPO, emptyList()), cwd = cwd))
        GitHubShot.Issues -> c.seed(GitHubWorkTab.Issues, cwd, issues = GitHubTabState(response = GitHubFixtures.issues, cwd = cwd), openMenu = 60, copiedNumber = 60)
        GitHubShot.Pulls -> c.seed(GitHubWorkTab.PullRequests, cwd, pullRequests = GitHubTabState(response = GitHubFixtures.pullRequests, cwd = cwd))
    }
    return c
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.snapGitHub(shot: GitHubShot, skin: TetherSkin, phone: Boolean, name: String = shot.id) {
    val c = seeded(shot)
    snapDraft(DraftShot.Empty, skin, phone, name, overlay = {
        GitHubWorkDialogFrame(c, onWork = { throw AssertionError("a golden must not work") }, onSetUp = {})
    })
}

/** Every dialog state × both Studio skins at the web's phone viewport. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class GitHubWorkPhoneScreenshotTest(private val shot: GitHubShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun dialog() = rule.snapGitHub(shot, skin, phone = true)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = GitHubShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** Every dialog state × both Studio skins at the web's tablet viewport. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class GitHubWorkExpandedScreenshotTest(private val shot: GitHubShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun dialog() = rule.snapGitHub(shot, skin, phone = false)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = GitHubShot.entries.flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}

/** PLAN §4: 1.3× font scale on a phone: the issues with a menu open, and the auth error. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class GitHubWorkFontScaleScreenshotTest(private val shot: GitHubShot, private val skin: TetherSkin) {
    @get:Rule val rule = createComposeRule()

    @Test fun dialog() = rule.snapGitHub(shot, skin, phone = true, "${shot.id}-font-1.3x")

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(GitHubShot.Issues, GitHubShot.AuthError).flatMap { s -> TetherSkin.entries.map { arrayOf<Any>(s, it) } }
    }
}
