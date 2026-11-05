package com.tether.app.ui.draft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tether.app.client.DraftComposerModel
import com.tether.app.client.DraftComposerState
import com.tether.app.client.DraftSessionOptionsModel
import com.tether.app.client.WorktreeField
import com.tether.app.ui.prefs.InMemoryDraftStore
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-y3dw: the sheet's 29dp header, its 48dp Close target and the one-line project row (workspace,
 * isolation, GH issues, PR), measured on the real frame at 360 / 412 dp, at the 1.3 font scale and on the
 * centred card. The seeds go through the real engine; no action may fire but Close.
 */
private const val LONG_FOLDER = "/home/dev/projects/tether-android-companion"

private class FrameSeed(cwd: String?, isolation: String?, branch: String?) {
    private val job = Job()
    val client = DraftTestClient(failOnSend = true)
    val model = DraftComposerModel(
        client = client,
        draftStore = InMemoryDraftStore(),
        scope = CoroutineScope(Dispatchers.Unconfined + job),
        currentWorkspace = { null },
    )
    val state: DraftComposerState
    val readiness: String

    init {
        model.onOrigin(DraftFixtures.ORIGIN)
        model.refresh()
        model.selectProviderAndModel("work", "m1")
        model.setText("hello")
        cwd?.let { model.setCwd(it) }
        isolation?.let { check(model.selectIsolation(it)) }
        branch?.let { check(model.setWorktreeField(WorktreeField.Branch, it)) }
        readiness = model.readiness()
        state = model.state.value
    }

    fun inputs() = DraftSheetInputs(
        draft = state,
        browser = draftBrowserInputs(state, client.providerCatalog.value, DraftFixtures.providers, com.tether.app.ui.chat.IcuJsCollator.forLocale(java.util.Locale.US), 1_700_000_000_000L),
        quickPicks = workspaceQuickPicks(emptyList(), "", DraftFixtures.ROOT, DraftFixtures.ROOT),
        workspaceRoot = DraftFixtures.ROOT,
        readiness = readiness,
        options = DraftSessionOptionsModel.of(state),
    )

    fun close() = job.cancel()
}

abstract class DraftHeaderAndRowBase {
    @get:Rule val rule = createComposeRule()
    private var seed: FrameSeed? = null
    protected var closes = 0

    /** True where the row must hold all four controls on one line (every width but the 1.3x phone cases). */
    protected open fun oneLine(isolation: String?) = true

    @After fun release() { seed?.close() }

    private fun show(cwd: String? = null, isolation: String? = null, branch: String? = null) {
        val s = FrameSeed(cwd, isolation, branch).also { seed = it }
        rule.setContent {
            TetherTheme {
                Box(Modifier.fillMaxSize()) {
                    DraftComposerFrame(s.inputs(), DraftSheetActions(onClose = { closes++ }))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun said(tag: String): String? = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

    private fun assertWithin(what: String, actual: Dp, expected: Dp, tolerance: Dp = 0.6.dp) =
        assertTrue("$what: $actual vs $expected", kotlin.math.abs(actual.value - expected.value) <= tolerance.value)

    @Test
    fun theHeaderIs29dp() {
        show()
        assertWithin("header height", bounds(DraftComposerTags.Header).height, 29.dp)
    }

    @Test
    fun theCloseTargetIs48dpAndTheStripBelowTheBarCloses() {
        show()
        val node = rule.onNodeWithTag(DraftComposerTags.Close, useUnmergedTree = true)
        val box = node.getUnclippedBoundsInRoot()
        assertTrue("target ${box.width} x ${box.height}", box.width >= 47.5.dp && box.height >= 47.5.dp)
        // 4dp inside the lower edge: below the 29dp header, answered by the sheet's own tap handler.
        node.performTouchInput { click(Offset(width / 2f, height - 4.dp.toPx())) }
        rule.waitForIdle()
        assertEquals(1, closes)
        // 4dp inside the side edges: inside the bar.
        node.performTouchInput { click(Offset(4.dp.toPx(), height / 2f)) }
        node.performTouchInput { click(Offset(width - 4.dp.toPx(), height / 2f)) }
        rule.waitForIdle()
        assertEquals(3, closes)
    }

    private fun assertRow(cwd: String?, isolation: String?, branch: String?) {
        show(cwd, isolation, branch)
        val sheet = bounds(DraftComposerTags.Sheet)
        val ws = bounds(DraftComposerTags.WorkspaceChip)
        val iso = bounds(WorktreeTags.Select)
        val issues = bounds(GitHubWorkTags.IssuesButton)
        val prs = bounds(GitHubWorkTags.PullRequestsButton)
        // The order is the reading order, whichever way the row breaks.
        assertTrue("workspace then isolation", ws.left < iso.left || ws.top < iso.top)
        assertTrue("issues then PRs", issues.left < prs.left)
        for ((name, b) in listOf("workspace" to ws, "isolation" to iso, "issues" to issues, "prs" to prs)) {
            assertTrue("$name right edge ${b.right} within ${sheet.right}", b.right <= sheet.right - 12.dp + 0.6.dp || sheet.width > 640.dp)
            assertTrue("$name left edge ${b.left}", b.left >= sheet.left)
        }
        // No overlap on a shared line.
        assertTrue("workspace clear of isolation", ws.top != iso.top || ws.right <= iso.left + 0.6.dp)
        assertTrue("issues clear of PRs", issues.right <= prs.left + 0.6.dp)
        if (oneLine(isolation)) {
            assertWithin("isolation top", iso.top, ws.top)
            assertWithin("issues top", issues.top, ws.top)
            assertWithin("prs top", prs.top, ws.top)
        }
        // The GitHub labels are whole.
        assertEquals("GH issues", said(GitHubWorkTags.IssuesButton))
        assertEquals("PR", said(GitHubWorkTags.PullRequestsButton))
    }

    @Test fun rowWithTheDefaultFolderLocal() = assertRow(null, null, null)

    @Test fun rowWithALongFolderLocal() = assertRow(LONG_FOLDER, null, null)

    @Test fun rowWithALongFolderAndAnExistingBranch() = assertRow(LONG_FOLDER, "checkout-branch", "feat/sidebar")
}

/** 360dp, font 1.0: one line. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi")
class DraftHeaderAndRow360Test : DraftHeaderAndRowBase()

/** 412dp, font 1.0: one line. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DraftHeaderAndRow412Test : DraftHeaderAndRowBase()

/** 412dp at the 1.3 font scale: one line with a local session; a long worktree label may take the seam break. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi", fontScale = 1.3f)
class DraftHeaderAndRow412Font13Test : DraftHeaderAndRowBase() {
    override fun oneLine(isolation: String?) = isolation == null
}

/** 360dp at 1.3: the row may break at its seam (nothing clips or overlaps, the labels stay whole). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-420dpi", fontScale = 1.3f)
class DraftHeaderAndRow360Font13Test : DraftHeaderAndRowBase() {
    override fun oneLine(isolation: String?) = false
}

/** The centred card (800dp): the row's controls sit on one line at their natural widths. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h900dp-mdpi")
class DraftHeaderAndRow800Test : DraftHeaderAndRowBase()
