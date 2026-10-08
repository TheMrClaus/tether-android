package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.tether.app.client.ConsentResult
import com.tether.app.protocol.AgentEvent
import com.tether.app.protocol.reduce.ev
import com.tether.app.ui.theme.TetherSkin
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
import kotlin.math.abs

/**
 * ta-4711 (W22): the Overview's "Review request" lands on, and focuses, the exact request card, as the web does
 * (dashboard.tsx:1424-1445 at 29537e0): `scrollIntoView({ block: "center" })` then `focus({ preventScroll: true })` on the
 * card itself (`div.chat-approval` / `div.chat-question`, `tabIndex={-1}`), never on its Allow. T1 a card taller than the
 * viewport, T2 a question at the list's end (clamped), T3 follow mode, T7 the card is no standing focus stop, T8 the
 * centring to the web's precision on a card that fits and is not clamped. (T4-T6 are the shell's hand-off:
 * MainShellReviewTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReviewFocusTest {
    @get:Rule val rule = createComposeRule()

    private val t0 = ApprovalFixtures.T
    private var folded by mutableStateOf(ChatFixtures.fold())
    private var review by mutableStateOf<String?>(null)
    private var shown = 0
    private var onShown: () -> Unit = {}
    private val follow = FollowState()
    private val listState = LazyListState()
    private var focus: FocusManager? = null

    private fun actions() = ConsentActions(
        sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.NotConnected },
        onOpenRun = {},
    )

    private fun history(turns: Int): List<AgentEvent> =
        (1..turns).flatMap { n -> ChatFixtures.turn("h$n", "Prompt $n", "Reply $n", t0 + n * 60_000L).toList() }

    private fun open(turn: String) = listOf(
        ev("turn_started", turn, ts = t0 + 900_000L) { put("idempotencyKey", "k-$turn") },
        ev("user_message_accepted", turn, ts = t0 + 900_000L) { put("text", "Run the migration.") },
    )

    private fun reads(n: Int, tag: String = "r") = (0 until n).map { "/srv/data/$tag-%02d/file.txt".format(it) }

    /** A permissions request with one read path per row (every row the same height). */
    private fun permissions(turn: String, requestId: String, paths: List<String>) = ev("approval_request", turn, ts = t0 + 900_000L) {
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

    private fun write(turn: String, requestId: String) = listOf(
        ev("tool_start", turn, ts = t0 + 900_000L) {
            put("toolId", "tool-$requestId"); put("name", "Write")
            putJsonObject("input") { put("file_path", "src/$requestId.ts"); put("content", "export const V = 1;\n") }
        },
        ev("approval_request", turn, ts = t0 + 900_000L) {
            put("requestId", requestId); put("toolId", "tool-$requestId"); put("name", "Write")
            putJsonObject("input") { put("file_path", "src/$requestId.ts"); put("content", "export const V = 1;\n") }
        },
    )

    private fun question(turn: String, requestId: String) = ev("question_request", turn, ts = t0 + 900_000L) {
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

    private fun show(sticky: Boolean) {
        follow.sticky = sticky
        rule.setContent {
            focus = LocalFocusManager.current
            ChatHost(TetherSkin.StudioDark, wellHeight = 700.dp) {
                ChatTranscript(
                    projection = folded.projection, tree = folded.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, consent = actions(), listState = listState, follow = follow,
                    reviewFocus = review, onReviewShown = { shown++; onShown() },
                )
            }
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()
    }

    private fun wellBounds() = rule.onNodeWithTag(WellTag).fetchSemanticsNode().boundsInRoot

    private fun head() = rule.onAllNodesWithTag("approval-card", useUnmergedTree = true)[0]

    private fun jumpKey() = rule.onAllNodesWithContentDescription("Jump to latest")

    /** T1: a card of 60 equal read paths, taller than the viewport, 60 turns above it, the list at the top. */
    @Test fun aCardTallerThanTheViewportLandsOnItsMiddleAndTheCardIsFocused() {
        folded = ChatFixtures.fold(*history(60).toTypedArray(), *open("t99").toTypedArray(), permissions("t99", "req-big", reads(60)))
        show(sticky = false)
        assertEquals(0, listState.firstVisibleItemIndex)
        head().assertDoesNotExistOrOffscreen()
        review = "req-big"
        settle()

        val well = wellBounds()
        val centre = (well.top + well.bottom) / 2f
        val rowAtCentre = rule.onAllNodesWithTag("grant-read", useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top <= centre && it.boundsInRoot.bottom >= centre }
        assertTrue("a path row of the card is under the viewport centre ($centre) in $well", rowAtCentre.isNotEmpty())
        fun androidx.compose.ui.semantics.SemanticsNode.words(): List<String> =
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.words() }
        val texts = rowAtCentre.flatMap { it.words() }
        val index = Regex("""r-(\d+)""").find(texts.joinToString(" ").filter { it.isLetterOrDigit() || it == '-' || it == ' ' || it == '/' || it == '.' })?.groupValues?.get(1)?.toInt()
        assertTrue("the row under the centre is the card's middle (of 60), got $index in $texts", index != null && abs(index - 30) <= 3)

        head().assertIsFocused()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        rule.onAllNodes(isFocused() and hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag("approval-allow", useUnmergedTree = true).assertCountEquals(0)
        assertEquals("onReviewShown once", 1, shown)
    }

    /** T2: a short question at the list's end: the end clamps the centring, the whole card is on screen, focused. */
    @Test fun aQuestionAtTheListsEndIsFullyOnScreenAndFocused() {
        folded = ChatFixtures.fold(*history(3).toTypedArray(), *open("t99").toTypedArray(), question("t99", "q-1"))
        show(sticky = false)
        review = "q-1"
        settle()
        val card = rule.onNodeWithTag("question-card", useUnmergedTree = true)
        card.assertIsDisplayed().assertIsFocused()
        val b = card.fetchSemanticsNode().boundsInRoot
        val well = wellBounds()
        assertTrue("fully inside the well: $b in $well", b.top >= well.top - 0.5f && b.bottom <= well.bottom + 0.5f)
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        rule.onAllNodes(isFocused() and hasSetTextAction()).assertCountEquals(0)
        assertEquals(1, shown)
    }

    /** T3: chat opened at its end (following), a tall card reviewed: the view moved up (the jump key shows) and a later row never pulls it back. */
    @Test fun followModeStopsOnAMoveUpAndANewRowDoesNotPullTheViewOffTheCard() {
        val base = arrayOf(*history(12).toTypedArray(), *open("t99").toTypedArray(), permissions("t99", "req-big", reads(60)))
        folded = ChatFixtures.fold(*base)
        show(sticky = true)
        jumpKey().assertCountEquals(0)
        val endIndex = listState.firstVisibleItemIndex
        review = "req-big"
        settle()
        assertTrue("the view moved up from $endIndex to ${listState.firstVisibleItemIndex}", listState.firstVisibleItemIndex < endIndex)
        jumpKey().assertCountEquals(1)
        head().assertIsFocused()
        fun firstRow() = rule.onAllNodesWithTag("grant-read", useUnmergedTree = true).fetchSemanticsNodes().first().let { it.positionInRoot.y to it.size.height }
        val before = firstRow()

        // A row arriving in the open turn (above the card, the review not cleared yet) never pulls the view off the card.
        folded = ChatFixtures.fold(
            *history(12).toTypedArray(), *open("t99").toTypedArray(),
            ev("message_started", "t99", ts = t0 + 950_000L) { put("blockId", "t99:m0") },
            ev("message_completed", "t99", ts = t0 + 950_000L) { put("blockId", "t99:m0"); put("text", "Waiting for your answer.") },
            permissions("t99", "req-big", reads(60)),
        )
        settle()
        assertEquals("the card stayed where it was", before.first, firstRow().first, 1f)
        jumpKey().assertCountEquals(1)
        assertEquals("centred once", 1, shown)
    }

    /** T3 (builder note): a row arriving in the same frame as onReviewShown causes no second scroll. */
    @Test fun aRowArrivingWithTheShownCallbackDoesNotCentreAgain() {
        val base = arrayOf(*history(12).toTypedArray(), *open("t99").toTypedArray(), permissions("t99", "req-big", reads(30)))
        folded = ChatFixtures.fold(*base)
        onShown = {
            folded = ChatFixtures.fold(
                *base,
                *ChatFixtures.turn("t100", "Anything else?", "Waiting for your answer.", t0 + 950_000L),
            )
        }
        show(sticky = false)
        review = "req-big"
        settle()
        settle()
        assertEquals("centred once, the request never re-centred by the row", 1, shown)
    }

    /** T7: the card is no standing focus stop: not focusable before the request, nor again once focus has left it. */
    @Test fun theCardIsFocusableOnlyFromTheRequestUntilFocusLeaves() {
        folded = ChatFixtures.fold(*history(2).toTypedArray(), *open("t99").toTypedArray(), *write("t99", "req-w").toTypedArray())
        show(sticky = false)
        val noFocusAction = SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus)
        head().assert(noFocusAction)
        review = "req-w"
        settle()
        head().assertIsFocused()
        review = null
        rule.runOnIdle { focus!!.clearFocus(force = true) }
        settle()
        head().assert(noFocusAction)
        rule.onAllNodes(isFocused()).assertCountEquals(0)
    }

    /**
     * T8: the centring to the web's precision (it lands within 0.2 px at the three web viewports). Two pending approvals in
     * the open turn; the FIRST is reviewed: short (it fits the viewport) and not at the list's end (the second card, taller,
     * follows it), so nothing clamps it. The card's centre, from its head's top to its last segment's bottom, is the
     * viewport's centre within 1 dp; the viewport is the list's whole box (the web's scrollport includes the padding).
     */
    @Test fun aShortCardThatIsNotClampedLandsOnTheViewportCentreWithinOneDp() {
        folded = ChatFixtures.fold(
            *history(8).toTypedArray(), *open("t99").toTypedArray(),
            *write("t99", "req-1").toTypedArray(),
            permissions("t99", "req-2", reads(30, "s")),
        )
        show(sticky = false)
        assertEquals(0, listState.firstVisibleItemIndex)
        review = "req-1"
        settle()

        val well = wellBounds()
        val head = head().fetchSemanticsNode().boundsInRoot
        // The tail's keys sit in the card's last 20 dp (CARD_PADDING): the card ends 20 dp under the lowest key.
        val keysBottom = rule.onAllNodesWithTag("approval-allow", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.bottom
        val density = rule.density.density
        val cardBottom = keysBottom + 20f * density
        val card = (head.top + cardBottom) / 2f
        val viewport = (well.top + well.bottom) / 2f
        assertTrue("the card ($head .. $cardBottom) fits the well $well", head.top > well.top && cardBottom < well.bottom)
        assertEquals("card centre $card vs viewport centre $viewport (px at $density)", viewport / density, card / density, 1f)
        head().assertIsFocused()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        rule.onAllNodesWithTag("approval-allow", useUnmergedTree = true).fetchSemanticsNodes().forEach {
            assertTrue("Allow is not focused", it.config.getOrNull(SemanticsProperties.Focused) != true)
        }
        assertEquals(1, shown)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrOffscreen() {
    val nodes = try {
        fetchSemanticsNode().boundsInRoot
    } catch (_: AssertionError) {
        return
    }
    assertTrue("the card starts off screen: $nodes", nodes.top > 700f || nodes.bottom < 0f)
}
