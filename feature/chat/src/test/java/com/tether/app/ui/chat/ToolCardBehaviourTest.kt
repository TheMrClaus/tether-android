package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.ui.theme.TetherSkin
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.2 behaviour: activity groups collapse and expand like the web's `<details>`, running runs stay
 * open, engine gates pick the rich cards, the interrupted-call evidence and the clamps toggle,
 * media loads through the loader and opens the viewer, and the git changes card requests hunks
 * exactly once per open file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolCardBehaviourTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val expanded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
    private val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")

    private fun show(
        fixture: ChatFixtures.Folded,
        richCodex: Boolean = false,
        richOpencode: Boolean = false,
        loader: ToolMediaLoader = ToolFixtures.FakeLoader(),
        listState: LazyListState = LazyListState(),
    ) {
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                    ChatTranscript(
                        projection = fixture.projection,
                        tree = fixture.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        onApproval = { _, _, _ -> },
                        onAnswer = { _, _, _ -> },
                        zone = ChatFixtures.zone,
                        listState = listState,
                        showTimeline = false,
                        richCodex = richCodex,
                        richOpencode = richOpencode,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The group header, scrolled into view first (follow mode pins the transcript's bottom). */
    private fun group() = rule.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("tool-activity-group")).let { rule.onNodeWithTag("tool-activity-group") }

    private val summary = "1 file read, 2 searches, 2 shell commands, 2 file edits, 1 file write, 1 web request, 1 tool call"

    @Test fun aFinishedRunCollapsesIntoItsSummaryAndExpandsOnTap() {
        show(ToolFixtures.tools)
        group().assert(hasContentDescription(summary)).assert(collapsed)
        // Only the media card (kept out of the group) is a tool card while collapsed.
        assertEquals(1, rule.onAllNodesWithTag("tool-card").fetchSemanticsNodes().size)
        group().performClick()
        rule.waitForIdle()
        group().assert(expanded)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("src/config.ts", substring = true))
        group().performClick()
        rule.waitForIdle()
        group().assert(collapsed)
        assertEquals(1, rule.onAllNodesWithTag("tool-card").fetchSemanticsNodes().size)
    }

    @Test fun aRunningRunIsOpenAndClosesWhenItFinishesLikeReactsDetails() {
        var fixture by mutableStateOf(ToolFixtures.running)
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                ChatTranscript(
                    projection = fixture.projection,
                    tree = fixture.tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    onApproval = { _, _, _ -> },
                    onAnswer = { _, _, _ -> },
                    zone = ChatFixtures.zone,
                    showTimeline = false,
                )
            }
        }
        rule.waitForIdle()
        group().assert(expanded)
        rule.onNodeWithText("RUNNING · 12S").assert(hasContentDescription("running · 12s"))
        // The reader closes it while it runs: it stays closed.
        group().performClick()
        rule.waitForIdle()
        group().assert(collapsed)
        // Everything finishes: the default flips to closed; still closed. Reopen, then a new run opens it.
        rule.runOnIdle {
            val done = foldTree(
                fixture.tree,
                ev("tool_end", "t1", ts = ToolFixtures.T_RUNNING) { put("toolId", "cmd-live"); put("output", "ok") },
                ev("tool_end", "t1", ts = ToolFixtures.T_RUNNING) { put("toolId", "bash-live"); put("output", "ok") },
            )
            fixture = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(done)!!, done)
        }
        rule.waitForIdle()
        group().assert(collapsed)
        rule.runOnIdle {
            val more = foldTree(fixture.tree, ev("tool_start", "t1", ts = ToolFixtures.T_RUNNING) { put("toolId", "again"); put("name", "Read"); put("input", kotlinx.serialization.json.buildJsonObject { put("file_path", "a") }) })
            fixture = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(more)!!, more)
        }
        rule.waitForIdle()
        // Running again: the default is open once more; the reader's old "closed" does not come back.
        group().assert(expanded)
        // A finished run the reader opens stays open.
        rule.runOnIdle {
            val done = foldTree(fixture.tree, ev("tool_end", "t1", ts = ToolFixtures.T_RUNNING) { put("toolId", "again"); put("output", "ok") })
            fixture = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(done)!!, done)
        }
        rule.waitForIdle()
        group().assert(collapsed).performClick()
        rule.waitForIdle()
        group().assert(expanded)
    }

    @Test fun theCodexGatePicksTheRichCardsAndTheDefaultCardOtherwise() {
        show(ToolFixtures.codexTools, richCodex = true)
        // The file change keeps its run open after the turn (hasFileChange).
        group().assert(expanded)
        rule.onAllNodesWithContentDescription("Codex command").onFirst().assertExists()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Codex file changes"))
        // A headerless patch is labelled "Patch 1", as in the web shot.
        rule.onNodeWithContentDescription("Unified diff for Patch 1").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("tool-card").fetchSemanticsNodes().size)
    }

    @Test fun withoutTheGateCodexToolsRenderAsGenericCards() {
        show(ToolFixtures.codexTools, richCodex = false)
        group().assert(expanded)
        assertEquals(0, rule.onAllNodesWithTag("rich-card").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("command_execution").onFirst().assertExists()
    }

    @Test fun codexTurnDetailsShowThePlanTheTurnDiffAndReviews() {
        show(ToolFixtures.codexDetails, richCodex = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Codex plan"))
        rule.onNodeWithContentDescription("Codex plan").assertExists()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Turn changes"))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Review failed"))
    }

    @Test fun codexTurnDetailsAreGatedLikeTheCards() {
        show(ToolFixtures.codexDetails, richCodex = false)
        assertEquals(0, rule.onAllNodesWithTag("rich-card").fetchSemanticsNodes().size)
    }

    @Test fun aTurnWhoseFileChangesCarryDiffsDropsTheAggregate() {
        show(ToolFixtures.corpusFinal("fixture-codex-app-server-rich"), richCodex = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Codex plan"))
        assertEquals(0, rule.onAllNodesWithContentDescription("Turn changes").fetchSemanticsNodes().size)
    }

    @Test fun anInterruptedCallKeepsTheClisTextBehindADisclosure() {
        show(ToolFixtures.corpusFinal("tool-lifecycle-progress"))
        group().assert(hasContentDescription("1 shell command, 1 file read · 1 interrupted")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("INTERRUPTED").assertExists()
        assertEquals(0, rule.onAllNodesWithText("[Request interrupted by user for tool use]").fetchSemanticsNodes().size)
        rule.onNodeWithText("What the CLI reported").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("[Request interrupted by user for tool use]").assertExists()
    }

    @Test fun opencodeTasksUnwrapTheirResultEnvelope() {
        show(ToolFixtures.opencodeTask, richOpencode = true)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Two test files: greeting and config."))
        assertEquals(0, rule.onAllNodesWithText("<task_result>", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Waiting for the subagent…"))
    }

    @Test fun mediaLoadsThroughTheLoaderOnceAndOpensTheViewer() {
        val loader = ToolFixtures.FakeLoader()
        show(ToolFixtures.tools, loader = loader)
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("View image full size"))
        assertEquals(listOf(ToolFixtures.CHART_URL), loader.loads.distinct())
        rule.onNodeWithContentDescription("View image full size").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Image viewer").assertExists()
        rule.onNodeWithText("100%").assertExists()
        rule.onNodeWithContentDescription("Zoom in").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("140%").assertExists()
        rule.onNodeWithContentDescription("Reset zoom").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("100%").assertExists()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithContentDescription("Image viewer").fetchSemanticsNodes().size)
    }

    @Test fun anUnloadablePictureSaysSo() {
        show(ToolFixtures.tools, loader = ToolFixtures.FakeLoader(MediaImage.TooLarge))
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Image too large to show"))
    }

    @Test fun longOutputClampsBehindTheToggle() {
        val long = (1..60).joinToString("\n") { "line $it" }
        val tree = foldTree(
            com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "b"); put("name", "Bash"); put("input", kotlinx.serialization.json.buildJsonObject { put("command", "seq 60") }) },
            ev("tool_end", "t1", ts = 1) { put("toolId", "b"); put("output", long) },
        )
        show(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree))
        group().performClick()
        rule.waitForIdle()
        val toggle = rule.onAllNodesWithContentDescription("more line", substring = true).onFirst()
        toggle.assertExists()
        toggle.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasContentDescription("Show less"))
    }

    @Test fun theGitChangesCardRequestsEachFileOnceAndShowsItsState() {
        val requested = mutableListOf<String>()
        var diffs by mutableStateOf(emptyMap<String, ServerMessage.GitDiffFile>())
        val summary = WorktreeDiffSummaryView(
            baseRef = "origin/main",
            commitsAhead = 2.0,
            committed = listOf(WorktreeDiffEntry("src/config.ts", "M")),
            uncommitted = listOf(WorktreeDiffEntry("logo.png", "??"), WorktreeDiffEntry("gone.ts", " D")),
        )
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                GitChangesCard(summary, diffs, onRequestFile = { requested += it })
            }
        }
        rule.onNodeWithText("Compared against origin/main").assertExists()
        rule.onNodeWithContentDescription("Modified src/config.ts").assert(collapsed).performClick()
        rule.waitForIdle()
        assertEquals(listOf("src/config.ts"), requested)
        rule.onNodeWithText("Loading diff…").assertExists()
        rule.runOnIdle { diffs = mapOf("src/config.ts" to ServerMessage.GitDiffFile("s1", "src/config.ts", "@@ -1 +1 @@\n-a\n+b", truncated = true, binary = false)) }
        rule.waitForIdle()
        rule.onNodeWithText("+b").assertExists()
        rule.onNodeWithText("Diff truncated at the size limit.").assertExists()
        // Close and reopen: cached, no second request.
        rule.onNodeWithContentDescription("Modified src/config.ts").performClick()
        rule.onNodeWithContentDescription("Modified src/config.ts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("src/config.ts"), requested)
        // One file open at a time.
        rule.runOnIdle { diffs = diffs + ("logo.png" to ServerMessage.GitDiffFile("s1", "logo.png", "", truncated = false, binary = true)) }
        rule.onNodeWithContentDescription("Untracked logo.png").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Binary file — no text diff.").assertExists()
        assertEquals(0, rule.onAllNodesWithText("+b").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Unstaged deleted gone.ts").performClick()
        rule.waitForIdle()
        assertEquals(listOf("src/config.ts", "gone.ts"), requested)
    }

    @Test fun theRepositoryDecodesDataUrisFetchesMediaRefsAndCaches() = runBlocking {
        val calls = mutableListOf<Pair<String, Long>>()
        val png = run {
            val out = java.io.ByteArrayOutputStream()
            val bmp = android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                calls += url to maxBytes
                if (url.endsWith("f.png")) return ToolMediaResult.TooLarge
                sink.write(png)
                return ToolMediaResult.Ok(png.size.toLong(), "image/png")
            }
        }
        val repo = ToolMediaRepository(source, rule.activity.cacheDir)
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val inline = repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,$b64")) as MediaImage.Ok
        assertEquals(48, inline.bitmap.width)
        assertTrue(calls.isEmpty())
        val url = "/api/tool-media/${"ab".repeat(32)}.png"
        assertTrue(repo.image(ToolMediaItem("image", "image/png", url)) is MediaImage.Ok)
        assertTrue(repo.image(ToolMediaItem("image", "image/png", url)) is MediaImage.Ok)
        assertEquals(listOf(url to MediaLimits.MAX_IMAGE_BYTES), calls)
        assertEquals(MediaImage.TooLarge, repo.image(ToolMediaItem("image", "image/png", "/api/tool-media/${"f".repeat(64)}.png")))
        // Not a server path, a non-image data URI, garbage base64: refused locally.
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "https://evil.test/x.png")))
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "text/html", "data:text/html;base64,PGI+")))
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,!!!")))
        assertEquals(2, calls.size)
        // A clip downloads once to the content-addressed cache, bounded by the server's cap.
        val clip = "/api/tool-media/${"cd".repeat(32)}.mp4"
        assertTrue(repo.video(ToolMediaItem("video", "video/mp4", clip)) is MediaVideo.Ok)
        assertTrue(repo.video(ToolMediaItem("video", "video/mp4", clip)) is MediaVideo.Ok)
        assertEquals(clip to MediaLimits.MAX_VIDEO_BYTES, calls.last())
        assertEquals(3, calls.size)
        assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", "/api/tool-media/../../x.mp4")))
    }

    @Test fun aDecompressionBombIsRefusedBeforeItsPixelsAreDecoded() {
        var decodes = 0
        val result = BoundedMediaDecoder.decode(ByteArray(8)) { _, options ->
            if (options.inJustDecodeBounds) {
                options.outWidth = 50_000
                options.outHeight = 50_000
            } else {
                decodes++
            }
            null
        }
        assertEquals(MediaImage.TooLarge, result)
        assertEquals(0, decodes)
        assertEquals(MediaImage.TooLarge, BoundedMediaDecoder.decode(ByteArray(8)) { _, _ -> throw OutOfMemoryError("x") })
        assertEquals(MediaImage.Failed, BoundedMediaDecoder.decode("not an image".toByteArray()))
    }
}
