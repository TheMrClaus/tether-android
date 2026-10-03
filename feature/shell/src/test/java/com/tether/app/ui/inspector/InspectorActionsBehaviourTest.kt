package com.tether.app.ui.inspector

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tether.app.client.ChangeRequestReading
import com.tether.app.client.TetherClient
import com.tether.app.client.WorktreeLogsReading
import com.tether.app.protocol.model.WorktreeInfo
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.chat.LinkOpener
import com.tether.app.ui.chat.LocalLinkOpener
import com.tether.app.ui.inspector.InspectorBoards.obj
import com.tether.app.ui.shell.ShellConsentClient
import com.tether.app.ui.statusline.screenshots.choiceFor
import com.tether.app.ui.theme.LocalReducedMotion
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.14: the inspector's Repository and Services actions as the web's (tether 90fbb9f
 * repository-panel.tsx:53-61, worktree-services-card.tsx:101-163, dashboard.tsx:1457-1459): the
 * pull request opens on one tap, the refresh asks again and the reply replaces the line, each script
 * has Run or Restart + Stop by its state, none asks first, and "Output of" toggles its log view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InspectorActionsBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private val recorder = LinkOpener { _: Context, href: String, _: Color -> opened += href }
    private val actions = mutableListOf<String>()

    private val worktree = WorktreeInfo(path = "/w", branch = "feat", status = "active", mode = "branch-off")

    private val scripts = obj(
        """{"sessionId":"s1","setupStatus":"ok","setupLog":[],"configWarnings":[],"scripts":[
           {"name":"dev","type":"service","command":"npm run dev","status":"running","port":5173,"proxyHost":null,"proxyAuthUrl":null,"proxyUnavailable":"not-configured"},
           {"name":"test","type":"script","command":"npm test","status":"exited","exitCode":0},
           {"name":"lint","type":"script","command":"npm run lint","status":"starting"}]}""",
    )

    private fun pr(url: String?) = ChangeRequestReading(
        obj("""{"number":12,"url":${url?.let { "\"$it\"" } ?: "null"},"state":"OPEN","isDraft":false,"reviewDecision":"APPROVED","mergeable":"MERGEABLE"}"""),
        unknown = false,
    )

    private fun model(cr: ChangeRequestReading? = pr("https://example.test/pr/12"), logs: WorktreeLogsReading? = null) =
        InspectorBoards.model(
            InspectorBoards.session(worktree = worktree),
            replies = InspectorReplies(worktreeScripts = scripts, changeRequest = cr, worktreeLogs = logs),
        )

    private var current by mutableStateOf<InspectorModel?>(null)

    private fun show(model: InspectorModel) {
        current = model
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLinkOpener provides recorder) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Inspector(
                            current!!, null, onSelectRun = {}, fileDiffs = null, onRequestFileDiff = {}, env = { InspectorBoards.env },
                            onRefreshChangeRequest = { actions += "refresh" },
                            onWorktreeScript = { name, action -> actions += "$action:$name" },
                            onWorktreeLogs = { name -> actions += "logs:$name" },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(description: String) {
        rule.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun count(description: String) =
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description)), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun tagCount(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    // ---- Repository -------------------------------------------------------------------------

    @Test fun thePullRequestOpensOnOneTap() {
        show(model())
        rule.onNodeWithTag(InspectorTags.PullRequestLink, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("opened at once, through the chat links' opener, no sheet", listOf("https://example.test/pr/12"), opened)
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun aPullRequestWithoutAnAddressIsPlainText() {
        show(model(cr = pr(null)))
        rule.onNodeWithText("Pull request #12", useUnmergedTree = true).performScrollTo()
        assertEquals(0, tagCount(InspectorTags.PullRequestLink))
        // The refresh is still there (dashboard.tsx:1457 always passes it).
        assertEquals(1, count("Refresh pull request status"))
    }

    @Test fun refreshAsksAgainAndTheReplyReplacesTheLine() {
        show(model(cr = ChangeRequestReading(null, unknown = false)))
        rule.onNodeWithText("No pull request", useUnmergedTree = true).performScrollTo()
        tap("Refresh pull request status")
        tap("Refresh pull request status")
        // One frame per tap; nothing of its own while it waits, as on the web.
        assertEquals(listOf("refresh", "refresh"), actions)
        rule.onNodeWithText("No pull request", useUnmergedTree = true).assertExists()
        // The reply: a failed lookup reads "PR status unavailable"; a found one, its headline.
        current = model(cr = ChangeRequestReading(null, unknown = true))
        rule.waitForIdle()
        rule.onNodeWithText("PR status unavailable", useUnmergedTree = true).assertExists()
        current = model()
        rule.waitForIdle()
        rule.onNodeWithText("Pull request #12", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Open · Approved", useUnmergedTree = true).assertExists()
    }

    // ---- Services ---------------------------------------------------------------------------

    @Test fun eachScriptHasTheWebsKeysForItsState() {
        show(model())
        // Running and starting: Restart and Stop, no Run.
        for (name in listOf("dev", "lint")) {
            assertEquals(1, count("Restart $name"))
            assertEquals(1, count("Stop $name"))
            assertEquals(0, count("Run $name"))
        }
        // Stopped: Run only.
        assertEquals(1, count("Run test"))
        assertEquals(0, count("Stop test"))
        assertEquals(0, count("Restart test"))
        // Every script has its "Output of".
        for (name in listOf("dev", "test", "lint")) assertEquals(1, count("Output of $name"))
    }

    @Test fun runStopAndRestartSendAtOnceWithNoConfirmation() {
        show(model())
        tap("Stop dev")
        tap("Restart lint")
        tap("Run test")
        assertEquals(listOf("stop:dev", "restart:lint", "start:test"), actions)
        // No sheet, no dialog: the same keys are still on screen.
        assertEquals(1, count("Stop dev"))
    }

    @Test fun outputOfTogglesTheLogViewAndAsksOnOpen() {
        show(model())
        assertEquals(0, tagCount(InspectorTags.ScriptLog))
        tap("Output of dev")
        assertEquals(listOf("logs:dev"), actions)
        rule.onNodeWithContentDescription("Output of dev", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        // No reply yet (or one for another script): the web's empty copy.
        rule.onNodeWithText("No output yet.", useUnmergedTree = true).assertExists()
        current = model(logs = WorktreeLogsReading("test", listOf("other output"), 0))
        rule.waitForIdle()
        rule.onNodeWithText("No output yet.", useUnmergedTree = true).assertExists()
        // Its reply: the lines.
        current = model(logs = WorktreeLogsReading("dev", listOf("VITE ready", "  ➜  Local: http://localhost:5173/"), 0))
        rule.waitForIdle()
        rule.onNodeWithText("VITE ready\n  ➜  Local: http://localhost:5173/", useUnmergedTree = true, substring = true).assertExists()
        // Another script's view replaces it (one open at a time) and asks for its output.
        tap("Output of test")
        assertEquals(listOf("logs:dev", "logs:test"), actions)
        assertEquals(1, tagCount(InspectorTags.ScriptLog))
        // Closing asks nothing.
        tap("Output of test")
        assertEquals(listOf("logs:dev", "logs:test"), actions)
        assertEquals(0, tagCount(InspectorTags.ScriptLog))
        rule.onNodeWithContentDescription("Output of test", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    /** `.log { max-height: 12rem; overflow: auto }`: a long output scrolls within, starting at its top. */
    @Test fun aLongLogScrollsWithinTwelveRemFromTheTop() {
        val lines = (1..200).map { "line $it" }
        show(model(logs = WorktreeLogsReading("dev", lines, 40)))
        tap("Output of dev")
        val pane = rule.onNodeWithTag(InspectorTags.ScriptLog, useUnmergedTree = true)
        pane.assert(hasScrollAction())
        val bounds = pane.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("at most 12rem tall: $height", height <= 192.dp + 0.5.dp)
        pane.assert(SemanticsMatcher("scrolled to the top") { it.config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f })
    }

    // ---- The host ---------------------------------------------------------------------------

    private class Recording(base: TetherClient = ShellConsentClient()) : TetherClient by base {
        val sent = mutableListOf<String>()
        val scripts = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
        val crs = MutableStateFlow<Map<String, ChangeRequestReading>>(emptyMap())
        val logs = MutableStateFlow<Map<String, WorktreeLogsReading>>(emptyMap())
        override val worktreeScripts: StateFlow<Map<String, JsonObject>> get() = scripts
        override val changeRequests: StateFlow<Map<String, ChangeRequestReading>> get() = crs
        override val worktreeLogs: StateFlow<Map<String, WorktreeLogsReading>> get() = logs
        override fun requestChangeRequest(sessionId: String, refresh: Boolean): Boolean { sent += "cr:$sessionId:$refresh"; return true }
        override fun controlWorktreeScript(sessionId: String, name: String, action: String): Boolean { sent += "script:$sessionId:$name:$action"; return true }
        override fun requestWorktreeLogs(sessionId: String, name: String): Boolean { sent += "logs:$sessionId:$name"; return true }
    }

    /** dashboard.tsx:1451-1459: the host hands the client's replies in and the keys' frames out, for the open session. */
    @Test fun theHostWiresTheActionsToTheClient() {
        val client = Recording()
        client.scripts.value = mapOf("s1" to scripts)
        client.crs.value = mapOf("s1" to pr("https://example.test/pr/12"))
        val vm = TetherViewModel(client)
        val session = InspectorBoards.session(worktree = worktree)
        rule.setContent {
            TetherTheme(choiceFor(TetherSkin.Studio)) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalLinkOpener provides recorder) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { InspectorHost(vm, session, null) }
                }
            }
        }
        rule.waitForIdle()
        tap("Refresh pull request status")
        tap("Stop dev")
        tap("Run test")
        tap("Output of dev")
        assertEquals(listOf("cr:s1:true", "script:s1:dev:stop", "script:s1:test:start", "logs:s1:dev"), client.sent)
        rule.onNodeWithText("No output yet.", useUnmergedTree = true).assertExists()
        client.logs.value = mapOf("s1" to WorktreeLogsReading("dev", listOf("listening"), 0))
        rule.waitForIdle()
        rule.onNodeWithText("listening", useUnmergedTree = true).assertExists()
    }
}
