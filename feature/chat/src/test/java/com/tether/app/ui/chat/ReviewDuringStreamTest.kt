package com.tether.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-qm8b (suspect 3): "Review request" lands on a card by lazy-list index maths done before it waits for frames. The rows
 * (a streaming reply, tools starting and ending, the card itself being answered and a new one arriving) keep changing while the
 * landing waits; the landing may be cancelled or land elsewhere, but never throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReviewDuringStreamTest {
    @get:Rule val rule = createComposeRule()

    private val t0 = ApprovalFixtures.T
    private var folded by mutableStateOf(ChatFixtures.fold())
    private var review by mutableStateOf<String?>(null)
    private var shown = 0
    private val follow = FollowState()
    private val listState = LazyListState()

    private fun actions() = ConsentActions(
        sessionId = "s1", origin = TEST_ORIGIN, lock = null, decided = emptySet(), questionUnavailable = null,
        onApproval = { _, _, _, _, _ -> ConsentResult.Sent },
        onAnswer = { _, _, _, _ -> ConsentResult.NotConnected },
        onOpenRun = {},
    )

    private fun permissions(requestId: String, paths: Int) = ev("approval_request", "live", ts = t0 + 900_000L) {
        put("requestId", requestId); put("toolId", "perm-$requestId"); put("name", "permissions")
        putJsonArray("choices") {
            addJsonObject { put("choiceId", "all"); put("label", "Allow all"); put("permissionGrant", "exact") }
            addJsonObject { put("choiceId", "deny"); put("label", "Deny") }
        }
        putJsonObject("metadata") {
            put("provider", "codex"); put("kind", "permissions")
            putJsonObject("requestedPermissions") { putJsonObject("fileSystem") { putJsonArray("read") { (0 until paths).forEach { add("/srv/data/r-%02d/file.txt".format(it)) } } } }
        }
    }

    private fun tool(n: Int) = listOf(
        ev("tool_start", "live", ts = t0 + 900_000L + n) { put("toolId", "x$n"); put("name", "Bash"); putJsonObject("input") { put("command", "echo $n") } },
        ev("tool_end", "live", ts = t0 + 900_000L + n) { put("toolId", "x$n"); put("output", kotlinx.serialization.json.JsonPrimitive("$n\n")); put("isError", false) },
    )

    @Test fun landingOnACardWhileTheRowsKeepChangingNeverThrows() {
        val base = ArrayList<AgentEvent>()
        for (n in 1..12) base += ChatFixtures.turn("h$n", "Prompt $n", "Reply $n", t0 + n * 60_000L).toList()
        base += ev("turn_started", "live", ts = t0 + 900_000L) { put("idempotencyKey", "k-live") }
        base += ev("user_message_accepted", "live", ts = t0 + 900_000L) { put("text", "Run the migration.") }
        base += permissions("req1", 6)
        folded = ChatFixtures.fold(*base.toTypedArray())
        follow.sticky = false
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 700.dp) {
                ChatTranscript(
                    projection = folded.projection, tree = folded.tree, showThinking = false, onFetchTurns = { _, _ -> },
                    zone = ChatFixtures.zone, consent = actions(), listState = listState, follow = follow,
                    reviewFocus = review, onReviewShown = { shown++ },
                )
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { review = "req1" }
        // Each step lands rows (before and after the card) and a delta while the landing waits for its frames.
        for (n in 1..40) {
            base += tool(n)
            if (n % 9 == 0) base += permissions("req${n + 1}", 3)
            rule.runOnIdle { folded = ChatFixtures.fold(*base.toTypedArray()) }
            rule.mainClock.advanceTimeBy(16)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        // And again for a request that arrives while the review is already asked.
        rule.runOnIdle { review = "req10" }
        for (n in 41..60) {
            base += tool(n)
            rule.runOnIdle { folded = ChatFixtures.fold(*base.toTypedArray()) }
            rule.mainClock.advanceTimeBy(16)
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }
}
