package com.tether.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.ui.TetherViewModel
import com.tether.app.ui.prefs.UiPrefs
import com.tether.app.ui.theme.LocalTetherTokens
import com.tether.app.ui.theme.TetherSkin
import com.tether.app.ui.theme.TetherTheme
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * ta-d0qg (W24): "Review request" with a sub-agent run tab selected. On the web the pending cards sit below the run panel on
 * every tab (chat-view.tsx:3399-3403, `.chat-approval` / `.chat-question` after the `activeRun ?` ternary), the Overview's
 * rAF finds `[data-request-id]` anywhere, centres it and focuses it, and the tab stays selected (dashboard.tsx:1424-1445);
 * the 8 s notice fires only when the card never renders. The app drops the request on a run tab, so the shell timed out with
 * REVIEW_NOT_FOUND; here the run tab lands on the card with the transcript's own routine ([landOnReviewCard]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReviewRequestRunTabTest {
    @get:Rule val rule = createComposeRule()

    private val runId = "t1::toolu_live"
    private var review by mutableStateOf<String?>(null)
    private var shown = 0
    private var input: InputModeManager? = null
    private var violet = Color.Unspecified
    private lateinit var vm: TetherViewModel

    private fun host(folded: ChatFixtures.Folded, mode: InputMode = InputMode.Touch) {
        val client = ChatTestClient().also { it.show(SubagentFixtures.session, folded) }
        vm = TetherViewModel(client)
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext())
        rule.setContent {
            input = LocalInputModeManager.current
            androidx.compose.runtime.CompositionLocalProvider(LocalChatDerivationDispatcher provides kotlinx.coroutines.Dispatchers.Unconfined) {
                TetherTheme(choiceFor(TetherSkin.Studio)) {
                    violet = LocalTetherTokens.current.violet
                    val projections by client.projections.collectAsStateWithLifecycle()
                    val sessions by client.sessions.collectAsStateWithLifecycle()
                    val s = sessions.firstOrNull { it.id == SubagentFixtures.session.id } ?: SubagentFixtures.session
                    ChatScreen(
                        vm = vm, session = s, projection = projections[s.id], workspaceRoot = "/w", prefs = prefs,
                        showWorkspaceHeader = false, reviewFocus = review, onReviewShown = { shown++ },
                    )
                }
            }
        }
        settle()
        rule.runOnIdle { vm.selectRun("s1", runId); input!!.requestInputMode(mode) }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun withEvents(vararg events: com.tether.app.protocol.AgentEvent): ChatFixtures.Folded {
        val tree = foldTree(SubagentFixtures.running.tree, *events)
        return ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
    }

    private val t0 = SubagentFixtures.T_SUB

    private fun permissions(requestId: String, paths: List<String>) = ev("approval_request", "t1", ts = t0) {
        put("requestId", requestId); put("toolId", "perm-$requestId"); put("name", "permissions")
        putJsonArray("choices") {
            addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
            addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
        }
        putJsonObject("metadata") {
            put("provider", "codex"); put("kind", "permissions")
            putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("read") { paths.forEach { add(it) } } } }
        }
    }

    private fun question(requestId: String) = ev("question_request", "t1", ts = t0) {
        put("requestId", requestId); put("toolId", "ask-$requestId")
        putJsonArray("questions") {
            addJsonObject {
                put("question", "Which database?"); put("header", "Database"); put("multiSelect", false)
                putJsonArray("options") {
                    addJsonObject { put("label", "Postgres"); put("description", "Relational") }
                    addJsonObject { put("label", "SQLite"); put("description", "Embedded") }
                }
            }
        }
    }

    private fun reads(n: Int) = (0 until n).map { "/srv/data/r-%02d/file.txt".format(it) }

    private fun well() = rule.onNodeWithTag("subrun-panel").fetchSemanticsNode().boundsInRoot

    @Test fun anApprovalCardOfARunTabIsCentredAndFocusedWithTheTabStillSelected() {
        host(withEvents(permissions("req-big", reads(60))))
        rule.onNodeWithTag("subrun-panel").assertIsDisplayed()
        review = "req-big"
        settle()

        assertEquals("onReviewShown once", 1, shown)
        assertEquals("the run tab stays selected", runId, vm.selectedRunIdBySession.value["s1"])
        rule.onNodeWithTag("subrun-panel").assertIsDisplayed()
        rule.onAllNodesWithTag("approval-card", useUnmergedTree = true)[0].assertIsFocused()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        rule.onAllNodes(isFocused() and hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag("approval-allow", useUnmergedTree = true).assertCountEquals(0)
        // A card taller than the viewport lands on its middle: the row under the viewport centre is the 60-row card's middle.
        val w = well()
        val centre = (w.top + w.bottom) / 2f
        val under = rule.onAllNodesWithTag("grant-read", useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top <= centre && it.boundsInRoot.bottom >= centre }
        assertTrue("a path row of the card is under the viewport centre ($centre) in $w", under.isNotEmpty())
        val named = under.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
        val index = Regex("""r-(\d+)""").find(named.joinToString(" "))?.groupValues?.get(1)?.toInt()
        assertTrue("the row under the centre is the card's middle (of 60), got $index in $named", index != null && abs(index - 30) <= 3)
    }

    @Test fun aQuestionCardOfARunTabIsFullyOnScreenAndFocused() {
        host(withEvents(question("q-1")))
        review = "q-1"
        settle()

        assertEquals("onReviewShown once", 1, shown)
        assertEquals("the run tab stays selected", runId, vm.selectedRunIdBySession.value["s1"])
        val card = rule.onNodeWithTag("question-card", useUnmergedTree = true)
        card.assertIsDisplayed().assertIsFocused()
        val b = card.fetchSemanticsNode().boundsInRoot
        val w = well()
        assertTrue("fully inside the panel: $b in $w", b.top >= w.top - 0.5f && b.bottom <= w.bottom + 0.5f)
        rule.onAllNodes(isFocused()).assertCountEquals(1)
    }

    @Test fun aRequestThatIsNotThereIsNeverShown() {
        host(withEvents(permissions("req-big", reads(3))))
        review = "req-nope"
        settle()
        assertEquals("a request that renders nowhere is not reported shown (the shell's notice does that)", 0, shown)
        assertEquals(runId, vm.selectedRunIdBySession.value["s1"])
    }

    /** ta-xhs4's ring, here too (D2): by keyboard the run tab's card draws the web's focus ring. */
    @Test fun byKeyboardTheRunTabsCardDrawsTheFocusRing() {
        host(withEvents(permissions("req-big", reads(3))), InputMode.Keyboard)
        review = "req-big"
        settle()
        val card = rule.onAllNodesWithTag("approval-card", useUnmergedTree = true)[0]
        card.assertIsFocused()
        val b = card.fetchSemanticsNode().boundsInRoot
        val image = rule.onRoot().captureToImage().toPixelMap()
        val px = image[Math.round(b.left - 3 * rule.density.density), Math.round(b.center.y)]
        assertTrue("the ring (violet $violet) is 3 dp outside the card, got $px", listOf(px.red - violet.red, px.green - violet.green, px.blue - violet.blue).all { abs(it) * 255f <= 8f })
    }
}
