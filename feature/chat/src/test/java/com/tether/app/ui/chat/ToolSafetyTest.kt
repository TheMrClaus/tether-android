package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.tether.app.client.TetherClient
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import com.tether.app.ui.components.TetherExpandablePre
import com.tether.app.ui.components.expandPeek
import com.tether.app.ui.theme.TetherSkin
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.2 round 3 (security review): a hostile or broken server cannot exhaust memory with pictures
 * (M1), stall the main thread on deeply nested JSON (M2) or on unbounded text layout (M3), stall a
 * card with a quadratic regex (L1), race clip downloads (L2), pass one format off as another (L3),
 * hang a load (L4), or fill the git card with unrequested hunks (L5, in core/net). Plus the
 * clip-cache wiring and the partial-download sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolSafetyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val cache: File get() = rule.activity.cacheDir
    private val origin = "https://tether.example"

    private val png: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    private val clip = ByteArray(2048) { (it * 3).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }

    private fun url(bytes: ByteArray, ext: String) = "/api/tool-media/${sha256Hex(bytes)}.$ext"

    // --- M1: pictures ---------------------------------------------------------------------------

    @Test fun atMostTwoPicturesLoadAtOnceAndNoneIsBufferedInMemory() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val sinks = mutableListOf<String>()
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                synchronized(sinks) { sinks += sink.javaClass.name }
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                release.await()
                sink.write(png)
                active.decrementAndGet()
                return ToolMediaResult.Ok(png.size.toLong(), "image/png")
            }
        }
        // Eight distinct pictures (distinct URLs → no cache hits), all requested at once.
        val repo = ToolMediaRepository(source, cache, origin)
        val items = (0 until 8).map { ToolMediaItem("image", "image/png", url(png, "png")) }
        val loads = (0 until 8).map { i -> async(Dispatchers.Default) { repo.image(items[i], full = i % 2 == 0) } }
        withTimeout(20_000) { while (active.get() < 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(200)
        assertEquals("the app-wide gate holds two", 2, peak.get())
        release.complete(Unit)
        loads.awaitAll()
        assertTrue("streamed to a file, never a byte array: $sinks", sinks.none { it.contains("ByteArrayOutputStream") })
        assertTrue("no temp file is left", File(cache, ToolMediaRepository.TMP_DIR).list().isNullOrEmpty())
    }

    @Test fun runningOutOfMemoryAnywhereIsTooLargeNeverACrash() = runBlocking {
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult = throw OutOfMemoryError("heap")
        }
        val repo = ToolMediaRepository(source, cache, origin)
        assertEquals(MediaImage.TooLarge, repo.image(ToolMediaItem("image", "image/png", url(png, "png"))))
        assertEquals(MediaVideo.TooLarge, repo.video(ToolMediaItem("video", "video/mp4", url(clip, "mp4"))))
    }

    @Test fun aRowShowsTwelveTilesThenAMoreTileThatOpensTheViewer() {
        val items = (0 until 20).map { ToolMediaItem("image", "image/png", "/api/tool-media/${"%064x".format(it)}.png") }
        val loader = ToolFixtures.FakeLoader()
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) { ToolMediaRow(items) }
            }
        }
        rule.waitForIdle()
        assertEquals(12, rule.onAllNodes(hasContentDescription("View image full size")).fetchSemanticsNodes().size)
        assertEquals(12, loader.loads.distinct().size)
        rule.onNodeWithContentDescription("+8 more pictures").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Image viewer").assertExists()
        // The viewer opened on the 13th picture: it can go back, and loaded that one full size.
        rule.onNodeWithContentDescription("Previous media").assertExists()
        assertTrue(loader.loads.contains(items[12].src))
    }

    // --- L3: magic bytes ------------------------------------------------------------------------

    @Test fun everyFormatMustBeWhatItsExtensionSays() {
        fun head(vararg b: Int) = ByteArray(12).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }
        assertTrue(MediaMagic.matches(head(0x89, 0x50, 0x4E, 0x47), "image/png"))
        assertTrue(MediaMagic.matches(head(0xFF, 0xD8, 0xFF, 0xE0), "image/jpeg"))
        assertTrue(MediaMagic.matches("GIF89a".toByteArray(), "image/gif"))
        assertTrue(MediaMagic.matches("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(), "image/webp"))
        assertTrue(MediaMagic.matches(clip, "video/mp4"))
        assertTrue(!MediaMagic.matches(head(0x89, 0x50, 0x4E, 0x47), "image/jpeg"))
        assertTrue(!MediaMagic.matches("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(), "image/webp"))
        assertTrue(!MediaMagic.matches("<svg".toByteArray(), "image/png"))
        assertTrue(!MediaMagic.matches(ByteArray(0), "image/png"))
        assertTrue(!MediaMagic.matches(png, "image/svg+xml"))
    }

    @Test fun aPngServedAsJpegOrADataUriDeclaringAnotherTypeIsRefused() = runBlocking {
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                sink.write(png)
                return ToolMediaResult.Ok(png.size.toLong(), "image/jpeg")
            }
        }
        val repo = ToolMediaRepository(source, cache, origin)
        // The name is the PNG's real sha256, so only the magic check can refuse it.
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/jpeg", url(png, "jpg"))))
        val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
        assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/jpeg", "data:image/jpeg;base64,$b64")))
        assertTrue(repo.image(ToolMediaItem("image", "image/png", "data:image/png;base64,$b64")) is MediaImage.Ok)
        // A clip that hashes to its name but is not an MP4.
        val notMp4 = "not a movie".toByteArray()
        val clipSource = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                sink.write(notMp4)
                return ToolMediaResult.Ok(notMp4.size.toLong(), "video/mp4")
            }
        }
        assertEquals(MediaVideo.Failed, ToolMediaRepository(clipSource, cache, origin).video(ToolMediaItem("video", "video/mp4", url(notMp4, "mp4"))))
    }

    // --- L2 / L4: clips and timeouts ------------------------------------------------------------

    @Test fun oneClipDownloadsOnceUnderItsLockAndLeavesNoPartialFile() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                calls.incrementAndGet()
                gate.await()
                sink.write(clip)
                return ToolMediaResult.Ok(clip.size.toLong(), "video/mp4")
            }
        }
        val repo = ToolMediaRepository(source, cache, origin)
        val item = ToolMediaItem("video", "video/mp4", url(clip, "mp4"))
        val both = (0 until 2).map { async(Dispatchers.Default) { repo.video(item) } }
        kotlinx.coroutines.delay(200)
        gate.complete(Unit)
        val results = both.awaitAll()
        assertTrue(results.all { it is MediaVideo.Ok })
        assertEquals("the second caller waited for the first and found the file", 1, calls.get())
        val dir = ToolMediaCache.dirFor(cache, origin)
        assertEquals(listOf("${sha256Hex(clip)}.mp4"), dir.list()!!.toList())
    }

    @Test fun aCancelledOrTimedOutDownloadLeavesNothingBehind() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                sink.write(clip, 0, 100)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val other = ByteArray(2048).also { clip.copyInto(it) ; it[2047] = 1 }
        val repo = ToolMediaRepository(source, cache, origin, imageTimeoutMs = 300, videoTimeoutMs = 300)
        // Cancelled by the caller.
        val job = launch(Dispatchers.Default) { repo.video(ToolMediaItem("video", "video/mp4", url(clip, "mp4"))) }
        withTimeout(20_000) { started.await() }
        job.cancelAndJoin()
        val dir = ToolMediaCache.dirFor(cache, origin)
        assertTrue("no .part after a cancellation: ${dir.list()?.toList()}", dir.list().isNullOrEmpty())
        // Timed out (L4): a picture and a clip that never finish fail on their own.
        withTimeout(20_000) {
            assertEquals(MediaImage.Failed, repo.image(ToolMediaItem("image", "image/png", url(png, "png"))))
            assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", url(other, "mp4"))))
        }
        assertTrue(dir.list().isNullOrEmpty())
        assertTrue(File(cache, ToolMediaRepository.TMP_DIR).list().isNullOrEmpty())
    }

    @Test fun aValidLookingClipUnderAnotherClipsNameIsDropped() = runBlocking {
        // Real MP4 magic, wrong content: only the re-hash of the closed file can refuse it.
        val impostor = clip.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                sink.write(impostor)
                return ToolMediaResult.Ok(impostor.size.toLong(), "video/mp4")
            }
        }
        val repo = ToolMediaRepository(source, cache, origin)
        assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", url(clip, "mp4"))))
        assertTrue(ToolMediaCache.dirFor(cache, origin).list().isNullOrEmpty())
    }

    @Test fun partialDownloadsAreSweptAfterSixtySeconds() {
        val dir = ToolMediaCache.dirFor(cache, origin).apply { mkdirs() }
        val now = 1_000_000_000_000L
        val fresh = File(dir, "a123.part").apply { writeBytes(ByteArray(1)); setLastModified(now - 30_000) }
        val justUnder = File(dir, "b123.part").apply { writeBytes(ByteArray(1)); setLastModified(now - 60_000) }
        val stale = File(dir, "c123.part").apply { writeBytes(ByteArray(1)); setLastModified(now - 61_000) }
        val clipFile = File(dir, "d.mp4").apply { writeBytes(ByteArray(1)); setLastModified(now - 61_000) }
        ToolMediaCache.evict(dir, now)
        assertTrue(fresh.exists() && justUnder.exists())
        assertTrue(!stale.exists())
        assertTrue("a finished clip is not a partial download", clipFile.exists())
    }

    @Test fun theMemoryCacheIsKeyedByTheSha256OfTheSource() {
        val src = "data:image/png;base64," + "A".repeat(10_000)
        val key = ToolMediaRepository.cacheKey(src, full = false)
        assertEquals(sha256Hex(src.toByteArray()) + ":thumb", key)
        assertTrue(ToolMediaRepository.cacheKey(src, full = true) != key)
    }

    // --- The clip-cache wiring (UiRoot) --------------------------------------------------------

    private class CacheClient(private val base: ChatTestClient = ChatTestClient()) : TetherClient by base {
        // Before the stored settings are read, a real client says "signed out, no server".
        override val configured = MutableStateFlow(false)
        override val serverUrl = MutableStateFlow<String?>(null)
        override val storedSettingsLoaded = MutableStateFlow(false)
    }

    @Test fun signOutAndAServerSwitchClearTheClipsAReLaunchDoesNot() = runBlocking {
        val cache = this@ToolSafetyTest.cache // read on the test thread (Robolectric's looper)
        val client = CacheClient()
        val here = ToolMediaCache.dirFor(cache, origin).apply { mkdirs() }
        File(here, "a.mp4").writeBytes(ByteArray(4))
        val job = launch(Dispatchers.Default) { syncToolMediaCache(client, cache) }
        kotlinx.coroutines.delay(200)
        assertTrue("nothing happens before the stored settings are read", File(here, "a.mp4").exists())
        client.configured.value = true
        client.serverUrl.value = origin
        client.storedSettingsLoaded.value = true
        kotlinx.coroutines.delay(300)
        assertTrue("the current server's clips stay", File(here, "a.mp4").exists())
        // A rotation: the effect is cancelled and relaunched with the same sign-in.
        job.cancelAndJoin()
        val again = launch(Dispatchers.Default) { syncToolMediaCache(client, cache) }
        kotlinx.coroutines.delay(300)
        assertTrue("a re-launch keeps them", File(here, "a.mp4").exists())
        // Another server: the previous server's clips go.
        client.serverUrl.value = "https://other.example"
        withTimeout(20_000) { while (here.exists()) kotlinx.coroutines.delay(20) }
        val there = ToolMediaCache.dirFor(cache, "https://other.example").apply { mkdirs() }
        File(there, "b.mp4").writeBytes(ByteArray(4))
        // Sign-out: everything goes.
        client.configured.value = false
        withTimeout(20_000) { while (File(cache, ToolMediaCache.DIR).exists()) kotlinx.coroutines.delay(20) }
        again.cancelAndJoin()
    }

    // --- M2: nested JSON ------------------------------------------------------------------------

    private fun nested(depth: Int): JsValue {
        var v: JsValue = JsStr("leaf")
        repeat(depth) { v = JsArr.of(v) }
        return v
    }

    @Test fun tenThousandDeepNestingPrintsInBoundedTime() {
        val deep = nested(10_000)
        val started = System.nanoTime()
        repeat(50) {
            assertEquals(601, summarize(deep).length)
            assertTrue(displayValue(deep).length <= DISPLAY_MAX + 1)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("50 × two bounded prints took $ms ms", ms < 2_000)
        // A summary is a prefix of the web's full print, so it is web-identical.
        val shallow = nested(40)
        val full = jsonStringifyPretty(shallow)
        assertEquals(full.substring(0, 600) + "…", summarize(shallow))
        // Past depth 64 a container prints … (the rest of the print stays well-formed around it).
        assertTrue(jsonStringifyPretty(nested(70)).contains("…"))
        assertTrue(!jsonStringifyPretty(nested(63)).contains("…"))
    }

    @Test fun printingStopsAtTheLimitWithoutVisitingTheRest() {
        // A million shared 1 KB strings: a full print would be a gigabyte; the summary is 601 characters.
        val kb = JsStr("x".repeat(1024))
        val huge = JsArr.of(List(1_000_000) { kb })
        val started = System.nanoTime()
        assertEquals(601, summarize(huge).length)
        assertEquals(DISPLAY_MAX + 1, displayValue(huge).length)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        // Without a limit, the depth cap alone keeps a 10k-deep value cheap.
        val t0 = System.nanoTime()
        assertTrue(jsonStringifyPretty(nested(10_000)).length < 10_000)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }

    @Test fun aStreamingCommandLaysOutItsNewestOutput() {
        // Just over the cap, in 100-character lines: the head is dropped, the newest line shows.
        val output = (1..700).joinToString("\n") { "line $it ".padEnd(99, '.') }
        val tree = foldTree(
            freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "c"); put("name", "command_execution"); put("input", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"command":"yes"}""")) },
            ev("tool_output_delta", "t1", ts = 1) { put("toolId", "c"); put("chunk", output) },
        )
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                ChatTranscript(
                    projection = LegacyProjectionAdapter.adaptOnce(tree)!!,
                    tree = tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    onApproval = { _, _, _ -> },
                    onAnswer = { _, _, _ -> },
                    zone = ChatFixtures.zone,
                    showTimeline = false,
                    richCodex = true,
                )
            }
        }
        rule.waitForIdle()
        // (A node taller than the viewport sends performScrollToNode round forever: read the tree.)
        fun texts() = rule.onAllNodes(hasText("line", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } }
        assertTrue("the streamed head is dropped", texts().any { it.startsWith("… ") && it.contains("earlier characters") })
        assertTrue("collapsed: only a peek", texts().none { it.contains("line 700 ") })
        rule.onNodeWithContentDescription("Show more").performClick()
        rule.waitForIdle()
        assertTrue("the newest line shows", texts().any { it.contains("line 700 ") })
        assertTrue("…and the oldest does not", texts().none { it.contains("line 1 .") })
    }

    @Test fun aFinishedCommandLaysOutItsHeadAndCountsTheRest() {
        val output = (1..700).joinToString("\n") { "line $it ".padEnd(99, '.') }
        val tree = foldTree(
            freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "c"); put("name", "command_execution"); put("input", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"command":"yes"}""")) },
            ev("tool_end", "t1", ts = 1) { put("toolId", "c"); put("output", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"text":${JsonPrimitive(output)},"exitCode":0,"status":"completed"}""")) },
        )
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                ChatTranscript(
                    projection = LegacyProjectionAdapter.adaptOnce(tree)!!,
                    tree = tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    onApproval = { _, _, _ -> },
                    onAnswer = { _, _, _ -> },
                    zone = ChatFixtures.zone,
                    showTimeline = false,
                    richCodex = true,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tool-activity-group").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show more").performClick()
        rule.waitForIdle()
        val texts = rule.onAllNodes(hasText("line", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } }
        assertTrue(texts.any { it.startsWith("line 1 ") && it.endsWith("more characters") })
        assertTrue(texts.none { it.contains("line 700 ") })
    }

    @Test fun aHugeStringOrObjectStopsAtTheLimit() {
        val big = JsObj.from((0 until 200_000).associate { "k$it" to JsStr("v".repeat(10)) })
        val started = System.nanoTime()
        assertEquals(601, summarize(big).length)
        assertEquals(DISPLAY_MAX + 1, displayValue(big).length)
        assertEquals(DISPLAY_MAX + 1, displayValue(JsStr("x".repeat(1_000_000))).length)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    // --- L1: the task envelope -------------------------------------------------------------------

    @Test fun anUnterminatedTaskEnvelopeIsLinear() {
        val hostile = "<task_result>".repeat(50_000) + "x".repeat(500_000)
        val started = System.nanoTime()
        assertEquals(hostile, taskResultText(hostile))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        assertEquals("a", taskResultText("<task_result>\r\na\r\n</task_result>"))
        assertEquals("", taskResultText("<task_result>\n</task_result>"))
        assertEquals("a\n", taskResultText("<task_result>a\n\n</task_result>"))
        assertEquals("x</task_result", taskResultText("<task_result>x</task_result</task_result>"))
    }

    // --- M3: text caps ---------------------------------------------------------------------------

    @Test fun payloadsAreCappedHeadWhenDoneAndTailWhileStreaming() {
        val text = (1..200_000).joinToString("") { (it % 10).toString() }
        val head = capHead(text)
        assertTrue(head.startsWith(text.substring(0, DISPLAY_MAX)))
        assertTrue(head.endsWith("more characters"))
        val tail = capTail(text)
        assertTrue(tail.endsWith(text.substring(text.length - DISPLAY_MAX)))
        assertTrue(tail.startsWith("… "))
        assertEquals("short", capHead("short"))
        assertEquals("short", capTail("short"))
        // A surrogate pair is never split.
        val emoji = "a".repeat(DISPLAY_MAX - 1) + "😀" + "b"
        assertTrue(!capHead(emoji).substring(0, DISPLAY_MAX).last().isHighSurrogate())
        assertEquals("x".repeat(DIFF_LINE_MAX) + "…", cutLine("x".repeat(DIFF_LINE_MAX + 5)))
    }

    @Test fun theMultiEditBudgetSpansTheWholeCard() {
        val edit = (1..150).map { EditDiffRow("add", "l$it") }
        val capped = capEdits(listOf(edit, edit, edit))
        assertEquals(listOf(150, 50), capped.map { it.shown.size })
        assertEquals(listOf(0, 250), capped.map { it.hidden })
        // One edit keeps the web's own per-block cap.
        val single = capEdits(listOf((1..5_001).map { EditDiffRow("add", "$it") })).single()
        assertEquals(200 to 4_801, single.shown.size to single.hidden)
    }

    @Test fun aClampedPreLaysOutOnlyItsPeek() {
        val text = (1..5_000).joinToString("\n") { "line $it" }
        assertEquals(63, expandPeek(text).count { it == '\n' })
        assertEquals(4096, expandPeek("x".repeat(100_000)).length)
        rule.setContent { ChatHost(TetherSkin.Machine) { TetherExpandablePre(text) } }
        rule.waitForIdle()
        fun shown() = rule.onAllNodes(hasText("line 1", substring = true)).fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("collapsed: a peek of ${shown().length} characters", shown().length < 1_000)
        rule.onNodeWithContentDescription("Show more").performClick()
        rule.waitForIdle()
        assertEquals(text.length, shown().length)
    }

    @Test fun aLongUnifiedDiffDrawsTwoThousandRowsThenCounts() {
        val lines = (1..3_000).joinToString("\n") { "+" + "y".repeat(if (it == 1) 5_000 else 3) }
        val change = """{"path":"big.ts","kind":"update","diff":${JsonPrimitive("@@ -0,0 +1,3000 @@\n$lines")}}"""
        val tree = foldTree(
            freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") },
            ev("tool_start", "t1", ts = 1) { put("toolId", "f"); put("name", "file_change"); put("input", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"changes":[$change]}""")) },
            ev("tool_end", "t1", ts = 1) { put("toolId", "f"); put("output", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"status":"completed","changes":[$change]}""")) },
        )
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                ChatTranscript(
                    projection = LegacyProjectionAdapter.adaptOnce(tree)!!,
                    tree = tree,
                    showThinking = false,
                    onFetchTurns = { _, _ -> },
                    onApproval = { _, _, _ -> },
                    onAnswer = { _, _, _ -> },
                    zone = ChatFixtures.zone,
                    showTimeline = false,
                    richCodex = true,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Show more").performClick()
        rule.onNodeWithTag("chat-transcript").performScrollToNode(hasText("+1,002 more lines")) // 3,001 rows; the path row costs one of the 2,000
        // The 5,000-character first line is cut at UNIFIED_LINE_MAX characters (it never wraps).
        val first = rule.onAllNodes(hasText("yyyy", substring = true)).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } }.maxByOrNull { it.length }!!
        assertEquals(UNIFIED_LINE_MAX + 1, first.length)
        assertTrue(rule.onAllNodesWithTag("unified-diff").fetchSemanticsNodes().isNotEmpty())
    }

    // --- Round 4 ---------------------------------------------------------------------------------

    private fun mediaOutput(n: Int, seed: Int): String = (0 until n).joinToString(",", "[", "]") {
        """{"type":"media_ref","mediaKind":"image","mediaType":"image/png","url":"/api/tool-media/${"%064x".format(seed * 1000 + it)}.png","bytes":9}"""
    }

    /** A Task whose 50 sub-agent tool entries each returned 12 pictures, and whose own result has 12 more. */
    private fun mediaHeavyTask(): ChatFixtures.Folded {
        val events = ArrayList<com.tether.app.protocol.AgentEvent>()
        events += ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k") }
        events += ev("tool_start", "t1", ts = 1) { put("toolId", "task"); put("name", "Task"); put("input", com.tether.app.protocol.TetherJson.parseToJsonElement("""{"description":"shots"}""")) }
        for (i in 0 until 50) {
            val items = """[{"key":"sub$i","kind":"tool","name":"Screenshot","input":{}},{"key":"sub$i","kind":"tool_result","isError":false,"output":${mediaOutput(12, i)}}]"""
            events += ev("subagent_message", "t1", ts = 1) { put("parentToolUseId", "task"); put("items", com.tether.app.protocol.TetherJson.parseToJsonElement(items)) }
        }
        events += ev("tool_end", "t1", ts = 1) { put("toolId", "task"); put("output", com.tether.app.protocol.TetherJson.parseToJsonElement(mediaOutput(12, 99))) }
        return ChatFixtures.fold(*events.toTypedArray())
    }

    @Test fun oneTileBudgetSpansTheWholeCardSubAgentEntriesIncluded() {
        assertEquals(listOf(5, 7, 0, 0), tileBudget(listOf(5, 12, 12, 3)))
        assertEquals(listOf(0), tileBudget(listOf(0)))
        val fixture = mediaHeavyTask()
        val block = ((fixture.tree["turnsById"] as JsObj)["t1"] as JsObj).let { (it["blocksById"] as JsObj)["task"] as JsObj }
        val subagent = block["subagent"] as? JsObj
        if (subagent == null || subagentEntries(subagent, false).size < 50) {
            // The fold keeps sub-agent entries under the parent: this fixture must exercise them.
            error("fixture has no sub-agent entries: ${block.keys}")
        }
        val plan = cardMediaPlan(subagent, block, showThinking = false)
        assertEquals(12, plan.byEntry.values.sum() + plan.card)
        assertEquals(12, plan.byEntry.values.first())
        assertEquals(0, plan.card)
        val loader = ToolFixtures.FakeLoader()
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) { ToolCard(block, showThinking = false) }
            }
        }
        rule.waitForIdle()
        assertEquals("the whole card loads 12 pictures", 12, loader.loads.distinct().size)
        assertTrue(rule.onAllNodesWithTag("tool-media-more").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun thumbnailsDecodeToWhatATileShows() {
        assertEquals(512, MediaLimits.THUMB_SIDE)
        assertEquals(1L * 1024 * 1024, MediaLimits.THUMB_DECODED_BYTES)
        assertEquals(2, BoundedMediaDecoder.plan(1024, 1024, 4, MediaLimits.THUMB_SIDE, MediaLimits.THUMB_DECODED_BYTES))
    }

    private fun diffOf(files: Int, rows: Int) = (0 until files).joinToString("\n") { f ->
        "diff --git a/f$f b/f$f\n--- a/f$f\n+++ b/f$f\n@@ -0,0 +1,$rows @@\n" + (1..rows).joinToString("\n") { "+r$it" }
    }

    @Test fun oneRowBudgetSpansAWholeDiffCard() {
        val files = parseUnifiedDiff(diffOf(100, 2_000))
        val plan = planDiffCard(listOf(files))
        assertEquals(1, plan.files.single().size) // 2,004 rows: the first file takes the whole budget
        assertEquals(DIFF_CARD_MAX_ROWS, plan.files.single().sumOf { it.rows })
        assertEquals(99, plan.hiddenFiles)
        assertEquals("+${"%,d".format(99 * 2_004)} more lines · +99 more files", plan.more)
        // A file-change card: each change's path row costs one, and a change past the budget is not drawn.
        val changes = (0 until 10).map { parseUnifiedDiff(diffOf(1, 500)) } + List(3) { emptyList() }
        val cards = planDiffCard(changes, headerCost = 1)
        assertTrue(cards.groupsDrawn < changes.size)
        assertEquals(changes.size - cards.groupsDrawn, cards.hiddenFiles)
        assertEquals(null, planDiffCard(listOf(parseUnifiedDiff(diffOf(2, 10)))).more)
    }

    @Test fun aHundredFileTurnDiffBuildsAtMostItsBudgetAndOnlyAPeekWhileCollapsed() {
        val text = diffOf(100, 2_000)
        rule.setContent { ChatHost(TetherSkin.Machine) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.verticalScrollForTest()) { CodexUnifiedDiff(text) } } }
        rule.waitForIdle()
        fun rowsBuilt() = rule.onAllNodes(hasText("r", substring = true)).fetchSemanticsNodes()
            .count { n -> n.config[SemanticsProperties.Text].joinToString("") { it.text }.matches(Regex("r\\d+")) }
        assertTrue("collapsed: a peek of rows (${rowsBuilt()})", rowsBuilt() <= DIFF_PEEK_ROWS)
        rule.onAllNodes(hasTestTag("diff-more")).fetchSemanticsNodes().single()
        rule.onNodeWithContentDescription("Show more").performClick()
        rule.waitForIdle()
        assertTrue("open: the card's budget (${rowsBuilt()})", rowsBuilt() <= DIFF_CARD_MAX_ROWS)
    }

    @Test fun aSweptFolderMidDownloadFailsTheLoadNotTheApp() = runBlocking {
        val cache = this@ToolSafetyTest.cache
        val source = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                // A sign-in change sweeps the folders while the bytes are on their way.
                ToolMediaCache.sync(cache, signedIn = false, origin = null)
                sink.write(if (url.endsWith(".mp4")) clip else png)
                return ToolMediaResult.Ok(0, "")
            }
        }
        val repo = ToolMediaRepository(source, cache, origin)
        assertTrue(repo.image(ToolMediaItem("image", "image/png", url(png, "png"))) is MediaImage.Failed)
        assertEquals(MediaVideo.Failed, repo.video(ToolMediaItem("video", "video/mp4", url(clip, "mp4"))))
    }

    @Test fun signInChangesAlsoSweepPictureDownloadsInFlight() {
        val tmp = File(cache, ToolMediaRepository.TMP_DIR).apply { mkdirs() }
        File(tmp, "img1.part").writeBytes(ByteArray(3))
        ToolMediaCache.sync(cache, signedIn = true, origin = origin)
        assertTrue(!tmp.exists())
    }

    @Test fun anOversizeDataPictureIsRefusedBeforeItIsHashed() = runBlocking {
        val repo = ToolMediaRepository(ToolMediaSource.Unavailable, cache, origin)
        val huge = "data:image/png;base64," + "A".repeat(((MediaLimits.MAX_IMAGE_BYTES / 3) * 4 + 8).toInt())
        val started = System.nanoTime()
        assertEquals(MediaImage.TooLarge, repo.image(ToolMediaItem("image", "image/png", huge)))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
    }

    @Test fun thePictureTimeoutStartsOnceALoadSlotIsHeld() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val holding = AtomicInteger()
        val slow = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                holding.incrementAndGet()
                release.await()
                sink.write(png)
                return ToolMediaResult.Ok(png.size.toLong(), "image/png")
            }
        }
        val fast = object : ToolMediaSource {
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                sink.write(png)
                return ToolMediaResult.Ok(png.size.toLong(), "image/png")
            }
        }
        val slowRepo = ToolMediaRepository(slow, cache, origin, imageTimeoutMs = 20_000)
        val fastRepo = ToolMediaRepository(fast, cache, origin, imageTimeoutMs = 300)
        val holders = (0 until 2).map { i -> async(Dispatchers.Default) { slowRepo.image(ToolMediaItem("image", "image/png", url(png, "png")), full = i == 0) } }
        withTimeout(20_000) { while (holding.get() < 2) kotlinx.coroutines.delay(10) }
        // Queued behind both slots for longer than its own timeout, then served.
        val queued = async(Dispatchers.Default) { fastRepo.image(ToolMediaItem("image", "image/png", "data:image/png;base64," + android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))) }
        kotlinx.coroutines.delay(800)
        release.complete(Unit)
        holders.awaitAll()
        assertTrue("a queued load is not timed out while it waits", queued.await() is MediaImage.Ok)
    }

    @Test fun theGitChangesCardCapsItsHunksAndFileLists() {
        val hunks = (1..3_000).joinToString("\n") { "+" + "z".repeat(if (it == 1) 2_000 else 2) }
        val summary = WorktreeDiffSummaryView("origin/main", 0.0, (0 until 600).map { WorktreeDiffEntry("f$it", "M") }, emptyList())
        rule.setContent {
            ChatHost(TetherSkin.Machine) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.verticalScrollForTest()) {
                    GitChangesCard(summary, mapOf("f0" to com.tether.app.protocol.ServerMessage.GitDiffFile("s", "f0", hunks, false, false)), onRequestFile = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(hasText("+100 more files")).fetchSemanticsNodes().single()
        assertEquals("only the first 500 file rows are drawn", 0, rule.onAllNodes(hasContentDescription("Modified f599")).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Modified f0").performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("+1,000 more lines")).fetchSemanticsNodes().single()
        val longest = rule.onAllNodes(hasText("zz", substring = true)).fetchSemanticsNodes()
            .maxOf { n -> n.config[SemanticsProperties.Text].joinToString("") { it.text }.length }
        assertEquals(UNIFIED_LINE_MAX + 1, longest)
    }
}

/** A tall, unconstrained host (a card inside a scroller, as the panel will be). */
private fun androidx.compose.ui.Modifier.verticalScrollForTest(): androidx.compose.ui.Modifier = composed {
    this.verticalScroll(androidx.compose.foundation.rememberScrollState())
}
