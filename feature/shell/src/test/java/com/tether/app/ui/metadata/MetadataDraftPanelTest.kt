package com.tether.app.ui.metadata

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.tether.app.client.PendingMetadataDraft
import com.tether.app.client.TetherClient
import com.tether.app.protocol.MetadataDraft
import com.tether.app.ui.shell.ShellConsentClient
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T8.5: the metadata draft panel (tether 90fbb9f components/metadata-draft-panel.tsx :12-137,
 * dashboard.tsx:1887) on the client's list: shown while any draft is pending, the latest one, in
 * its kind's words; × and Dismiss drop that one (the earlier one shows next, the last closes it);
 * each Copy puts exactly its text on the clipboard and says "Copied".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class MetadataDraftPanelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private class Drafts(base: TetherClient = ShellConsentClient()) : TetherClient by base {
        val list = MutableStateFlow<List<PendingMetadataDraft>>(emptyList())
        override val metadataDrafts: StateFlow<List<PendingMetadataDraft>> get() = list
        override fun dismissMetadataDraft(requestId: String) = list.update { l -> l.filterNot { it.requestId == requestId } }
    }

    private val client = Drafts()

    private fun show(vararg drafts: PendingMetadataDraft) {
        client.list.value = drafts.toList()
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true) { MetadataDraftPanelHost(client) }
            }
        }
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun clipboard(): String =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()

    private val commit = PendingMetadataDraft("c1", MetadataDraft.CommitMessage("fix(inspector): keep the band rhythm\n\nThe draft keys sit under the branch."))
    private val pr = PendingMetadataDraft("p1", MetadataDraft.PullRequest("Add draft keys", "## Summary\n- Draft commit message\n- Draft pull request"))

    @Test fun nothingPendingDrawsNothing() {
        show()
        assertTrue(!exists(MetadataDraftTags.Dialog))
        client.list.value = listOf(commit)
        rule.waitForIdle()
        assertTrue(exists(MetadataDraftTags.Dialog))
    }

    @Test fun aCommitMessageCopiesItsText() {
        show(commit)
        assertTrue(shows("Draft commit message"))
        assertTrue(shows("Copy message"))
        tap(MetadataDraftTags.CopyMessage)
        assertEquals("fix(inspector): keep the band rhythm\n\nThe draft keys sit under the branch.", clipboard())
        assertTrue(shows("Copied"))
        assertTrue(!shows("Copy message"))
    }

    @Test fun aPullRequestCopiesTitleAndBodyEachOnItsOwn() {
        show(pr)
        assertTrue(shows("Draft pull request"))
        assertTrue(shows("TITLE"))
        assertTrue(shows("BODY"))
        tap(MetadataDraftTags.CopyTitle)
        assertEquals("Add draft keys", clipboard())
        assertEquals(1, rule.onAllNodesWithText("Copied", useUnmergedTree = true).fetchSemanticsNodes().size)
        tap(MetadataDraftTags.CopyBody)
        assertEquals("## Summary\n- Draft commit message\n- Draft pull request", clipboard())
        // One copied key at a time (metadata-draft-panel.tsx:17, 43): the title's reads "Copy" again.
        assertEquals(1, rule.onAllNodesWithText("Copied", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Copy", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun aFailureSaysTheServersWordsOrTheFallback() {
        show(PendingMetadataDraft("e1", MetadataDraft.Failure("")), PendingMetadataDraft("e2", MetadataDraft.Failure("No staged changes to describe.")))
        assertTrue(shows("Draft failed"))
        assertTrue(shows("No staged changes to describe."))
        tap(MetadataDraftTags.Dismiss)
        assertTrue(shows("Draft failed"))
        assertTrue(shows("The draft could not be generated."))
    }

    @Test fun dismissingDropsTheShownDraftAndTheEarlierOneShowsNext() {
        show(commit, pr)
        // The latest reply surfaces (metadata-draft-panel.tsx:23).
        assertTrue(shows("Draft pull request"))
        tap(MetadataDraftTags.Close)
        assertEquals(listOf("c1"), client.list.value.map { it.requestId })
        assertTrue(shows("Draft commit message"))
        tap(MetadataDraftTags.Dismiss)
        assertEquals(emptyList<String>(), client.list.value.map { it.requestId })
        assertTrue(!exists(MetadataDraftTags.Dialog))
    }

    @Test fun aNewReplyReplacesTheShownOne() {
        show(commit)
        client.list.value = listOf(commit, pr)
        rule.waitForIdle()
        assertTrue(shows("Draft pull request"))
        assertTrue(!shows("Draft commit message"))
    }
}
