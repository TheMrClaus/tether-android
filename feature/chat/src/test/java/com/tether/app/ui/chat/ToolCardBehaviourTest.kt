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

    private val png: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    /** An MP4-shaped clip: `ftyp` at offset 4 (L3 checks the magic before anything plays). */
    private val clipBytes = ByteArray(4096) { (it * 7).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    private val pngUrl by lazy { "/api/tool-media/${sha256Hex(png)}.png" }
    private val clipUrl by lazy { "/api/tool-media/${sha256Hex(clipBytes)}.mp4" }

    /** Serves [png] / [clipBytes] (or [swap] bytes: a server replacing content under a known name). */
    private inner class Source(var swap: ByteArray? = null) : ToolMediaSource {
        val calls = mutableListOf<Pair<String, Long>>()
        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
            calls += url to maxBytes
            if (url.endsWith("f.png")) return ToolMediaResult.TooLarge
            val bytes = swap ?: if (url.endsWith(".mp4")) clipBytes else png
            sink.write(bytes)
            return ToolMediaResult.Ok(bytes.size.toLong(), if (url.endsWith(".mp4")) "video/mp4" else "image/png")
        }
    }

    private val origin = "https://tether.example"

    @Test fun theRepositoryDecodesDataUrisFetchesMediaRefsAndCaches() = runBlocking {
        val source = Source()
        val calls = source.calls
        val repo = ToolMediaRepository(source, rule.activity.cacheDir, origin)
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        val inline = repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,$b64")) as MediaImage.Ok
        assertEquals(48, inline.bitmap.width)
        assertTrue(calls.isEmpty())
        assertTrue(repo.image(ToolMediaItem("image", "image/png", pngUrl)) is MediaImage.Ok)
        assertTrue(repo.image(ToolMediaItem("image", "image/png", pngUrl)) is MediaImage.Ok)
        assertEquals(listOf(pngUrl to MediaLimits.MAX_IMAGE_BYTES), calls)
        assertEquals(MediaImage.TooLarge, repo.image(ToolMediaItem("image", "image/png", "/api/tool-media/${"f".repeat(64)}.png")))
        // Not a server path, garbage base64: refused locally.
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "https://evil.test/x.png")))
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,!!!")))
        assertEquals(2, calls.size)
        // A clip downloads once to the server's content-addressed cache, bounded by the server's cap.
        val first = repo.video(ToolMediaItem("video", "video/mp4", clipUrl)) as MediaVideo.Ok
        assertEquals(ToolMediaCache.dirFor(rule.activity.cacheDir, origin), first.file.parentFile)
        assertTrue(first.file.readBytes().contentEquals(clipBytes))
        assertTrue(repo.video(ToolMediaItem("video", "video/mp4", clipUrl)) is MediaVideo.Ok)
        assertEquals(clipUrl to MediaLimits.MAX_VIDEO_BYTES, calls.last())
        assertEquals(3, calls.size)
        assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", "/api/tool-media/../../x.mp4")))
        // Without a paired origin there is nowhere to keep a clip.
        assertEquals(MediaVideo.Failed, ToolMediaRepository(source, rule.activity.cacheDir, null).video(ToolMediaItem("video", "video/mp4", clipUrl)))
    }

    @Test fun aDataUriIsDecodedOnlyForAnImageType() = runBlocking {
        val repo = ToolMediaRepository(Source(), rule.activity.cacheDir, origin)
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        // The SAME valid PNG bytes: decoded as image/png, refused under any other declared type.
        assertTrue(repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,$b64")) is MediaImage.Ok)
        for (type in listOf("text/html", "image/svg+xml", "application/octet-stream", "image/PNG", "video/mp4")) {
            assertEquals(type, MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "data:$type;base64,$b64")))
        }
    }

    @Test fun bytesThatDoNotHashToTheirNameAreNeverShownOrKept() = runBlocking {
        val source = Source(swap = "not the named content".toByteArray())
        val repo = ToolMediaRepository(source, rule.activity.cacheDir, origin)
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", pngUrl)))
        assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", clipUrl)))
        val dir = ToolMediaCache.dirFor(rule.activity.cacheDir, origin)
        assertTrue("nothing kept: ${dir.list()?.toList()}", dir.list().isNullOrEmpty())
        // Even a real PNG under another image's name is refused.
        source.swap = png
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", "/api/tool-media/${"0".repeat(64)}.png")))
    }

    @Test fun theClipCacheIsScopedToOneSignInAndEvicted() {
        val cache = rule.activity.cacheDir
        val here = ToolMediaCache.dirFor(cache, origin).apply { mkdirs() }
        val there = ToolMediaCache.dirFor(cache, "https://other.example").apply { mkdirs() }
        java.io.File(here, "a.mp4").writeBytes(ByteArray(10))
        java.io.File(there, "b.mp4").writeBytes(ByteArray(10))
        assertTrue("the origin never lands on disk", here.name.matches(Regex("[0-9a-f]{16}")))
        ToolMediaCache.sync(cache, signedIn = true, origin = origin)
        assertTrue(java.io.File(here, "a.mp4").exists())
        assertTrue("another server's clips go", !there.exists())
        ToolMediaCache.sync(cache, signedIn = false, origin = origin)
        assertTrue("a sign-out drops them all", !java.io.File(cache, ToolMediaCache.DIR).exists())

        // Eviction: past the age, or oldest first past the size cap.
        here.mkdirs()
        val now = 1_000_000_000_000L
        val old = java.io.File(here, "old.mp4").apply { writeBytes(ByteArray(10)); setLastModified(now - ToolMediaCache.MAX_AGE_MS - 1) }
        val a = java.io.File(here, "a.mp4").apply { writeBytes(ByteArray(60)); setLastModified(now - 3_000) }
        val b = java.io.File(here, "b.mp4").apply { writeBytes(ByteArray(60)); setLastModified(now - 2_000) }
        val stuck = java.io.File(here, "c.mp4.part").apply { writeBytes(ByteArray(1)); setLastModified(now - 120_000) }
        ToolMediaCache.evict(here, now, maxBytes = 100)
        assertTrue(!old.exists() && !stuck.exists())
        assertTrue("oldest goes first past the cap", !a.exists() && b.exists())
    }

    @Test fun groupTogglesDropWhereTheDefaultIsRead() {
        val toggles = GroupToggles()
        val group = ChatItem.ToolGroup("t1", "b1", "1 shell command", running = true, hasErrors = false, defaultOpen = true, open = true, startsGroup = true)
        toggles.toggle(group) // closed while running
        assertEquals(false, toggles.resolve(group.key, true))
        // The default flips (the run finished) and flips back (a new call) before the next paint:
        // the first read of the new default drops the toggle, so the old "closed" cannot return.
        assertEquals(false, toggles.resolve(group.key, false))
        assertEquals(true, toggles.resolve(group.key, true))
        assertEquals(1, toggles.version)
    }

    @Test fun theEngineGatesNeedTheProviderAndItsGeneration() {
        assertTrue(isRichCodexSession("codex", "codex-app-server-v2"))
        assertTrue(!isRichCodexSession("codex", null))
        assertTrue(!isRichCodexSession("codex", "codex-exec-v1"))
        assertTrue(!isRichCodexSession("claude", "codex-app-server-v2"))
        assertTrue(!isRichCodexSession(null, null))
        assertTrue(isRichOpencodeSession("opencode", "opencode-serve-v2"))
        assertTrue(!isRichOpencodeSession("opencode", null))
        assertTrue(!isRichOpencodeSession("opencode", "opencode-run-v1"))
        assertTrue(!isRichOpencodeSession("codex", "opencode-serve-v2"))
    }

    @Test fun aDecompressionBombIsRefusedBeforeItsPixelsAreDecoded() {
        val file = java.io.File.createTempFile("bomb", ".png", rule.activity.cacheDir)
        var decodes = 0
        val result = BoundedMediaDecoder.decode(file) { _, options ->
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
        assertEquals(MediaImage.TooLarge, BoundedMediaDecoder.decode(file) { _, _ -> throw OutOfMemoryError("x") })
        file.writeText("not an image")
        assertEquals(MediaImage.Failed, BoundedMediaDecoder.decode(file))
        // A thumbnail decodes under the tighter bound: 4096² samples to 1024² (4 MB).
        assertEquals(4, BoundedMediaDecoder.plan(4096, 4096, 4, MediaLimits.THUMB_SIDE, MediaLimits.THUMB_DECODED_BYTES))
        file.delete()
    }
}
