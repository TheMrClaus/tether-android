package com.tether.app.ui.chat

import com.tether.app.client.DeclaredLengthSink
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.ui.video.PlayableSource
import com.tether.app.ui.video.VideoPhase
import com.tether.app.ui.video.VideoPlayer
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ta-coik.68: the clip download and what plays from it, as plain JUnit (no Robolectric: the compose
 * state these classes hold is written without a rule here, and nothing needs the platform; the
 * decoder is a fake). One GET, never a Range; `readAt` waits for the bytes, however long, as the web's
 * `<video>` does (no stall rule); a finished, verified clip is cached and the next play makes no request; the
 * sha256 / MP4 checks decide only that cache rename, never whether a playing clip plays; a failed clip plays
 * again with a fresh download; every exit that is not the rename deletes the partial file.
 */
class ToolClipsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val origin = "https://tether.example"
    private val clip = ByteArray(4096) { (it * 7).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    private val sha get() = sha256Hex(clip)
    private val src get() = "/api/tool-media/$sha.mp4"
    private val cache: File get() = tmp.root
    private val dir: File get() = ToolMediaCache.dirFor(cache, origin)

    private fun partFiles(): List<String> = dir.list()?.filter { it.endsWith(".part") } ?: emptyList()

    /** Serves [clip] in [chunks] pieces, each after [gates] opens (none: all at once). Records every request. */
    private inner class Server(
        private val body: ByteArray = clip,
        private val chunks: Int = 1,
        private val gates: List<CompletableDeferred<Unit>> = emptyList(),
        private val declare: Boolean = false,
        private val result: ((ByteArray) -> ToolMediaResult)? = null,
    ) : ToolMediaSource {
        val calls = AtomicInteger()
        val urls = mutableListOf<String>()
        val caps = mutableListOf<Long>()
        val cancelled = CompletableDeferred<Unit>()
        val written = AtomicInteger()

        override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
            calls.incrementAndGet()
            synchronized(urls) { urls += url; caps += maxBytes }
            try {
                if (declare) (sink as? DeclaredLengthSink)?.declaredLength(body.size.toLong())
                val size = body.size / chunks
                for (i in 0 until chunks) {
                    gates.getOrNull(i)?.await()
                    val from = i * size
                    val to = if (i == chunks - 1) body.size else from + size
                    sink.write(body, from, to - from)
                    written.addAndGet(to - from)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
            return result?.invoke(body) ?: ToolMediaResult.Ok(body.size.toLong(), "video/mp4")
        }
    }

    private fun download(source: ToolMediaSource) = ClipDownload(source, cache, origin, src, sha)

    private fun ClipReader.read(position: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        val n = readAt(position, out, 0, size)
        return if (n < 0) ByteArray(0) else out.copyOf(n)
    }

    private fun <T> eventually(what: String, check: () -> T?): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) {
            check()?.let { return it }
            Thread.sleep(10)
        }
        fail("never: $what")
        error("unreachable")
    }

    // --- one GET, readAt waits ----------------------------------------------------------------------

    @Test fun oneGetServesEveryReadAndNoReadMakesARequest() = runBlocking {
        val server = Server()
        val d = download(server)
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        eventually("whole") { if (d.outcome() is MediaVideo.Ok) true else null }
        // Any positions, any order (a seek): all from the one download.
        assertTrue(reader.read(0, 100).contentEquals(clip.copyOf(100)))
        assertTrue(reader.read(3000, 200).contentEquals(clip.copyOfRange(3000, 3200)))
        assertTrue(reader.read(10, 5).contentEquals(clip.copyOfRange(10, 15)))
        assertEquals("one GET, no Range request per read", 1, server.calls.get())
        assertEquals(listOf(src), server.urls)
        assertEquals("the server's own cap (MAX_MEDIA_BYTES)", listOf(MediaLimits.MAX_VIDEO_BYTES), server.caps)
        assertEquals(100L * 1024 * 1024, MediaLimits.MAX_VIDEO_BYTES)
        assertEquals("a read past the end is the end", 0, reader.read(clip.size.toLong(), 10).size)
        reader.close()
    }

    @Test fun aReadOfBytesNotThereYetBlocksUntilTheyArrive() = runBlocking {
        val gates = List(3) { CompletableDeferred<Unit>() }
        val server = Server(chunks = 4, gates = gates)
        val d = download(server)
        d.start()
        val reader = ClipReader(d)
        val waiting = AtomicReference<List<Boolean>>(emptyList())
        reader.onWaiting = { w -> waiting.updateAndGet { it + w } }
        // The first chunk (1024 bytes) is the only one served until a gate opens.
        gates[0].complete(Unit)
        assertTrue(reader.open())
        eventually("first chunk") { if (d.bytesWritten >= 1024) true else null }
        assertTrue(reader.read(0, 512).contentEquals(clip.copyOf(512)))
        assertTrue("it had the bytes: it never waited", waiting.get().isEmpty())
        // A read at 2000 needs the second chunk: it blocks on its own thread until the gate opens.
        val result = AtomicReference<ByteArray>()
        val done = CountDownLatch(1)
        val thread = Thread { result.set(reader.read(2000, 40)); done.countDown() }.also { it.start() }
        assertFalse("blocked on bytes that are not there", done.await(300, TimeUnit.MILLISECONDS))
        assertEquals(listOf(true), waiting.get())
        gates[1].complete(Unit)
        assertTrue(done.await(20, TimeUnit.SECONDS))
        thread.join()
        assertTrue(result.get().contentEquals(clip.copyOfRange(2000, 2040)))
        assertEquals("it stopped waiting", listOf(true, false), waiting.get())
        gates[2].complete(Unit)
        eventually("whole") { if (d.outcome() is MediaVideo.Ok) true else null }
        assertEquals(1, server.calls.get())
        reader.close()
    }

    private fun blockedReadEnds(stop: (ClipReader, ClipDownload) -> Unit) = runBlocking {
        val open = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val d = download(Server(chunks = 2, gates = listOf(open, CompletableDeferred())))
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try { reader.read(4000, 10) } catch (e: IOException) { failure.set(e) }
            done.countDown()
        }.start()
        Thread.sleep(150)
        assertEquals("still blocked on bytes that never come", 1L, done.count)
        stop(reader, d)
        assertTrue("the blocked read returned", done.await(20, TimeUnit.SECONDS))
        assertTrue("it failed rather than returning bytes", failure.get() is IOException)
        d.close()
    }

    @Test fun aBlockedReadFailsWhenItsReaderIsClosed() = blockedReadEnds { reader, _ -> reader.close() }

    @Test fun aBlockedReadFailsWhenTheDownloadIsStopped() = blockedReadEnds { _, d -> d.close() }

    // --- the cache, verification ----------------------------------------------------------------------

    @Test fun aVerifiedFinishIsCachedAndTheNextPlayMakesNoRequest() = runBlocking {
        val server = Server(chunks = 2)
        val d = download(server)
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        val final = eventually("verified") { d.outcome() as? MediaVideo.Ok }
        assertEquals(File(dir, "$sha.mp4"), final.file)
        assertTrue(final.file.readBytes().contentEquals(clip))
        assertTrue("no partial file is left", partFiles().isEmpty())
        // A reader that was reading the part file while it was renamed keeps reading.
        assertTrue(reader.read(0, 64).contentEquals(clip.copyOf(64)))
        reader.close()
        // The next play: from the cache, no GET.
        val again = Server()
        val d2 = download(again)
        d2.start()
        val r2 = ClipReader(d2)
        assertTrue(r2.open())
        assertEquals(clip.size.toLong(), r2.size)
        assertTrue(r2.read(100, 50).contentEquals(clip.copyOfRange(100, 150)))
        assertEquals("a cached clip is not fetched again", 0, again.calls.get())
        r2.close()
    }

    @Test fun aClipReleasedOnItsLastByteIsStillCheckedAndCached() = runBlocking {
        lateinit var d: ClipDownload
        // The session is left just as the last byte lands: the release arrives before the source has answered.
        val server = object : ToolMediaSource {
            val calls = AtomicInteger()
            override suspend fun fetch(url: String, maxBytes: Long, sink: OutputStream): ToolMediaResult {
                calls.incrementAndGet()
                (sink as? DeclaredLengthSink)?.declaredLength(clip.size.toLong())
                sink.write(clip)
                d.close()
                return ToolMediaResult.Ok(clip.size.toLong(), "video/mp4")
            }
        }
        d = download(server)
        d.start()
        eventually("the verified clip is in the cache") { File(dir, "$sha.mp4").takeIf { it.isFile && it.length() == clip.size.toLong() } }
        assertTrue("no partial file is left", partFiles().isEmpty())
        val again = Server()
        val d2 = download(again)
        d2.start()
        eventually("served from the cache") { d2.outcome() as? MediaVideo.Ok }
        assertEquals("the next play makes no request", 0, again.calls.get())
    }

    @Test fun bytesThatDoNotHashToTheirNameAreNeverCachedButStillPlay() = runBlocking {
        val impostor = clip.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val d = download(Server(body = impostor, chunks = 2))
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        eventually("settled") { d.outcome() }
        // The one-shot answer (a whole, verified clip) is no; the cache is untouched.
        assertEquals(MediaVideo.Failed, d.outcome())
        assertTrue("nothing is cached", dir.list()!!.none { it.endsWith(".mp4") })
        // But the clip the player is reading plays: every byte, to the end, and the download is not a failure.
        assertNull("not a download failure", d.failure())
        assertTrue(reader.open())
        assertTrue(reader.read(0, 64).contentEquals(impostor.copyOf(64)))
        assertTrue(reader.read(100, 1).contentEquals(impostor.copyOfRange(100, 101)))
        assertTrue(reader.read(4000, 96).contentEquals(impostor.copyOfRange(4000, 4096)))
        assertEquals("the end of the clip", 0, reader.read(impostor.size.toLong(), 10).size)
        reader.close()
        // The partial is deleted with the download, as every exit that is not the cache rename.
        d.close()
        assertTrue("nothing is left", dir.list().isNullOrEmpty())
    }

    @Test fun notAnMp4IsNeverCachedEvenUnderItsOwnHashButStillPlays() = runBlocking {
        val notMp4 = ByteArray(2048) { 5 }
        val d = ClipDownload(Server(body = notMp4), cache, origin, "/api/tool-media/${sha256Hex(notMp4)}.mp4", sha256Hex(notMp4))
        d.start()
        val reader = ClipReader(d)
        eventually("settled") { d.outcome() }
        assertEquals(MediaVideo.Failed, d.outcome())
        assertTrue(dir.list()!!.none { it.endsWith(".mp4") })
        assertTrue(reader.open())
        assertTrue(reader.read(0, 16).contentEquals(notMp4.copyOf(16)))
        reader.close()
        d.close()
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun aClipTheOneShotUseFindsUnverifiedLeavesNoPartialBehind() = runBlocking {
        val impostor = clip.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val d = download(Server(body = impostor))
        d.run()
        assertEquals(MediaVideo.Failed, d.outcome())
        assertTrue("no player reads it: nothing is kept", dir.list().isNullOrEmpty())
    }

    // --- failures, stall, stop ------------------------------------------------------------------------

    @Test fun aRedirectAnswerIsBlockedAndOpensNothing() = runBlocking {
        val d = download(Server(body = ByteArray(0), result = { ToolMediaResult.Blocked(302) }))
        d.start()
        assertFalse(ClipReader(d).open())
        assertEquals(MediaVideo.Blocked, d.failure())
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun aTooLargeClipIsTooLargeAndKeepsNothing() = runBlocking {
        val d = download(Server(body = ByteArray(0), result = { ToolMediaResult.TooLarge }))
        d.start()
        assertFalse(ClipReader(d).open())
        assertEquals(MediaVideo.TooLarge, d.failure())
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun aStalledClipKeepsWaitingLikeTheBrowserAndPlaysOnWhenBytesComeAgain() = runBlocking {
        val gates = listOf(CompletableDeferred(Unit), CompletableDeferred<Unit>()) // the second chunk is late
        val server = Server(chunks = 2, gates = gates)
        val d = download(server)
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        val got = AtomicReference<ByteArray>()
        val failed = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try { got.set(reader.read(4000, 10)) } catch (e: IOException) { failed.set(e) }
            done.countDown()
        }.start()
        // No byte arrives for far longer than the old stall rule's limit would have been in a test: it only waits.
        assertFalse("the read waits, it is not ended by a timer", done.await(1500, TimeUnit.MILLISECONDS))
        assertNull("no failure", d.failure())
        assertNull("no verdict", d.outcome())
        assertFalse("the GET is still going", server.cancelled.isCompleted)
        assertEquals("the part is kept", 1, partFiles().size)
        gates[1].complete(Unit)
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertNull(failed.get())
        assertTrue(got.get().contentEquals(clip.copyOfRange(4000, 4010)))
        assertTrue("it finished and was cached", eventually("verified") { d.outcome() as? MediaVideo.Ok } is MediaVideo.Ok)
        reader.close()
    }

    @Test fun aSlowClipThatKeepsProgressingFinishes() = runBlocking {
        // 6 chunks, 150 ms apart (~0.9 s in all): no whole-clip limit either.
        val gates = List(6) { CompletableDeferred<Unit>() }
        val server = Server(chunks = 6, gates = gates)
        val d = download(server)
        d.start()
        for (g in gates) {
            delay(150)
            g.complete(Unit)
        }
        val final = eventually("whole") { d.outcome() }
        assertTrue("it finished: $final", final is MediaVideo.Ok)
    }

    @Test fun aPartHeldByALiveDownloadSurvivesAnotherClipsStartEvenPastTheSixtySecondMark() = runBlocking {
        val gates = listOf(CompletableDeferred(Unit), CompletableDeferred<Unit>())
        val first = download(Server(chunks = 2, gates = gates))
        first.start()
        val reader = ClipReader(first)
        assertTrue(reader.open())
        val held = first.partFile!!
        // It has been quiet for more than the sweep's 60 s by mtime: a stalled download, not a dead one.
        assertTrue(held.setLastModified(System.currentTimeMillis() - 5 * 60_000L))
        // Another clip starts in the same server directory (every start sweeps it).
        val otherBytes = ByteArray(2048) { 9 }.also { "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> it[i] = c.code.toByte() } }
        val otherSha = sha256Hex(otherBytes)
        val second = ClipDownload(Server(body = otherBytes), cache, origin, "/api/tool-media/$otherSha.mp4", otherSha)
        second.start()
        eventually("the other clip finished") { second.outcome() as? MediaVideo.Ok }
        assertTrue("the held part is still there", held.isFile)
        // And it completes into the cache once its bytes come.
        gates[1].complete(Unit)
        eventually("the first finished") { first.outcome() as? MediaVideo.Ok }
        assertTrue(File(dir, "$sha.mp4").isFile)
        reader.close()
    }

    @Test fun aPartNoDownloadHoldsIsStillSweptAfterSixtySeconds() = runBlocking {
        val orphan = File(dir.apply { mkdirs() }, "${sha}123.part").apply { writeBytes(ByteArray(10)) }
        assertTrue(orphan.setLastModified(System.currentTimeMillis() - 5 * 60_000L))
        val second = download(Server())
        second.start()
        eventually("done") { second.outcome() as? MediaVideo.Ok }
        assertFalse("an old part nobody holds is swept", orphan.exists())
    }

    @Test fun closeStopsTheGetAndDeletesThePartial() = runBlocking {
        val gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val server = Server(chunks = 2, gates = listOf(gate, CompletableDeferred()))
        val d = download(server)
        d.start()
        assertTrue(ClipReader(d).open())
        assertEquals(1, partFiles().size)
        d.close()
        withTimeout(20_000) { server.cancelled.await() }
        assertTrue("the partial is gone", partFiles().isEmpty())
        assertTrue(dir.list().isNullOrEmpty())
    }

    @Test fun theDeclaredLengthIsTheSizeBeforeTheBodyIsWhole() = runBlocking {
        val gates = listOf(CompletableDeferred(Unit), CompletableDeferred<Unit>())
        val d = download(Server(chunks = 2, gates = gates, declare = true))
        d.start()
        val reader = ClipReader(d)
        assertTrue(reader.open())
        assertEquals(clip.size.toLong(), reader.size)
        gates[1].complete(Unit)
        eventually("whole") { d.outcome() }
        assertEquals(clip.size.toLong(), reader.size)
        reader.close()
    }

    // --- the registry: nothing on render, shared by both views, released on every exit ----------------

    private class FakePlayer(val reader: PlayableSource, val onFailed: () -> Unit) : VideoPlayer {
        override var phase: VideoPhase = VideoPhase.Opening
        override val playing: Boolean get() = false
        override val control: android.widget.MediaController.MediaPlayerControl get() = throw UnsupportedOperationException()
        var released = false
        override fun attachSurface(surface: android.view.Surface) = Unit
        override fun detachSurface(surface: android.view.Surface) = Unit
        override fun pause() = Unit
        override fun release() {
            released = true
            reader.close()
        }
    }

    private fun registry(source: ToolMediaSource, players: MutableList<FakePlayer>) =
        ToolClipRegistry(
            source, cache, { origin }, CoroutineScope(Dispatchers.Unconfined),
            makePlayer = { reader, failed -> FakePlayer(reader, failed).also { players += it } },
        )

    @Test fun nothingIsDecodedOrRequestedUntilPlay() {
        val players = mutableListOf<FakePlayer>()
        val server = Server()
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        assertEquals(ClipState.Idle, clip.inline.state)
        assertEquals(ClipState.Idle, clip.viewer.state)
        assertTrue("no decoder made on render", players.isEmpty())
        assertEquals("no request on render", 0, server.calls.get())
        assertNull("only an mp4 tool-media path is a clip", registry.clip("/api/tool-media/${sha}.png"))
        assertNull(registry.clip("https://evil.test/$sha.mp4"))
        assertNull(registry.clip("/api/tool-media/../../x.mp4"))
    }

    @Test fun theInlineAndTheViewerReadTheSameOneDownload() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val gate = CompletableDeferred<Unit>()
        val server = Server(chunks = 2, gates = listOf(gate, gate))
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        clip.inline.play()
        clip.viewer.play()
        assertEquals("one player per view, the second inline play is a no-op", 2, players.size)
        gate.complete(Unit)
        eventually("whole") { if (clip.currentDownload?.outcome() is MediaVideo.Ok) true else null }
        assertEquals("both views, one GET", 1, server.calls.get())
        assertTrue(ClipReader::class.java.isInstance(players[0].reader) && players[0].reader !== players[1].reader)
        // The viewer going away leaves the inline clip and the download alone.
        clip.viewer.release()
        assertTrue(players[1].released)
        assertFalse(players[0].released)
    }

    @Test fun releaseStopsTheDownloadDeletesThePartialAndReturnsTheRowToIdle() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val gate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val server = Server(chunks = 2, gates = listOf(gate, CompletableDeferred()))
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        eventually("bytes arrived") { if ((clip.currentDownload?.bytesWritten ?: 0) > 0) true else null }
        assertEquals(1, partFiles().size)
        registry.releaseAll()
        withTimeout(20_000) { server.cancelled.await() }
        assertTrue("the partial is deleted with it", partFiles().isEmpty())
        assertTrue(players.all { it.released })
        assertEquals(ClipState.Idle, clip.inline.state)
        // And it can play again: a new download.
        clip.inline.play()
        eventually("a new GET") { if (server.calls.get() == 2) true else null }
        registry.releaseAll()
    }

    @Test fun aCompletedClipSurvivesReleaseInTheCacheAndReplaysWithoutARequest() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val server = Server()
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        eventually("whole") { if (clip.currentDownload?.outcome() is MediaVideo.Ok) true else null }
        registry.releaseAll()
        assertTrue("the verified clip stays", File(dir, "$sha.mp4").isFile)
        clip.inline.play()
        eventually("replayed") { if (clip.currentDownload?.outcome() is MediaVideo.Ok) true else null }
        assertEquals("next play: no GET", 1, server.calls.get())
        registry.releaseAll()
    }

    @Test fun aFailedDownloadIsTheRowsErrorWithItsKind() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val blocked = registry(Server(body = ByteArray(0), result = { ToolMediaResult.Blocked(302) }), players)
        val clip = blocked.clip(src)!!
        clip.inline.play()
        eventually("settled") { clip.currentDownload?.outcome() }
        players.single().let { it.phase = VideoPhase.Failed; it.onFailed() }
        assertEquals(ClipState.Error(MediaVideo.Blocked), clip.inline.state)
        blocked.releaseAll()
        assertEquals(ClipState.Idle, clip.inline.state)
    }

    /** A source whose first [failures] answers are a failed fetch, then the clip. */
    private fun flaky(failures: Int): Server {
        val answered = AtomicInteger()
        return Server(result = { if (answered.incrementAndGet() <= failures) ToolMediaResult.Failed() else ToolMediaResult.Ok(clip.size.toLong(), "video/mp4") })
    }

    private fun failFirst(players: List<FakePlayer>, index: Int = 0) =
        players[index].let { it.phase = VideoPhase.Failed; it.onFailed() }

    @Test fun aFailedClipIsPlayedAgainWithAFreshDownloadFromTheInlineBox() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val server = flaky(failures = 1)
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        eventually("failed") { clip.currentDownload?.failure() }
        failFirst(players)
        assertTrue(clip.inline.state is ClipState.Error)
        // The browser's native controls replay a failed clip: a play after a failure starts again.
        clip.inline.play()
        assertEquals("a new player", 2, players.size)
        eventually("a second GET") { if (server.calls.get() == 2) true else null }
        eventually("it finished") { clip.currentDownload?.outcome() as? MediaVideo.Ok }
        assertFalse("not the old failed state", clip.inline.state is ClipState.Error)
        assertTrue("the failed player is gone", players[0].released)
        assertTrue(File(dir, "$sha.mp4").isFile)
        registry.releaseAll()
    }

    @Test fun aFailedClipIsPlayedAgainFromTheViewerAndWhenTheViewerIsReopened() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val server = flaky(failures = 2)
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        eventually("failed") { clip.currentDownload?.failure() }
        failFirst(players)
        // The viewer opens on the failed clip: it does not inherit the old failure, it downloads again (and fails once more here).
        clip.viewer.play()
        eventually("a second GET") { if (server.calls.get() == 2) true else null }
        eventually("failed again") { clip.currentDownload?.failure() }
        failFirst(players, 1)
        assertTrue(clip.viewer.state is ClipState.Error)
        // Closed and reopened: a third, fresh one, which now succeeds.
        clip.viewer.release()
        assertEquals(ClipState.Idle, clip.viewer.state)
        clip.viewer.play()
        eventually("a third GET") { if (server.calls.get() == 3) true else null }
        eventually("it finished") { clip.currentDownload?.outcome() as? MediaVideo.Ok }
        assertFalse(clip.viewer.state is ClipState.Error)
        registry.releaseAll()
    }

    @Test fun aPlayWhileTheClipIsLoadingOrPlayingStartsNothingNew() = runBlocking {
        val players = mutableListOf<FakePlayer>()
        val gate = CompletableDeferred<Unit>()
        val server = Server(chunks = 2, gates = listOf(gate, gate))
        val registry = registry(server, players)
        val clip = registry.clip(src)!!
        clip.inline.play()
        clip.inline.play()
        clip.inline.play()
        assertEquals(1, players.size)
        gate.complete(Unit)
        eventually("whole") { clip.currentDownload?.outcome() as? MediaVideo.Ok }
        clip.inline.play()
        assertEquals("a playing clip is not restarted", 1, players.size)
        assertEquals(1, server.calls.get())
        registry.releaseAll()
    }

    @Test fun aClipWithNoServerIsNotPlayable() {
        val players = mutableListOf<FakePlayer>()
        val registry = ToolClipRegistry(Server(), cache, { null }, CoroutineScope(Dispatchers.Unconfined), makePlayer = { r, f -> FakePlayer(r, f).also { players += it } })
        val clip = registry.clip(src)!!
        clip.inline.play()
        assertEquals(ClipState.Error(MediaVideo.Failed), clip.inline.state)
        assertTrue(players.isEmpty())
    }

    @Test fun theOpenViewerLivesInTheRegistryAndOnlyItsCloseOrAnotherItemReleasesItsPlayer() {
        val players = mutableListOf<FakePlayer>()
        val registry = registry(Server(chunks = 2, gates = listOf(CompletableDeferred(), CompletableDeferred())), players)
        val other = "/api/tool-media/${"b".repeat(64)}.mp4"
        val items = listOf(ToolMediaItem("video", "video/mp4", src), ToolMediaItem("video", "video/mp4", other))
        val a = registry.clip(src)!!
        val b = registry.clip(other)!!
        assertNull(registry.openViewerSrc)
        registry.openViewer(items, 0)
        a.viewer.play()
        // A rotation that rebuilds every row changes nothing here: the state is the registry's.
        assertEquals(src, registry.openViewerSrc)
        assertEquals(items, registry.viewerItems)
        assertFalse(players.single().released)
        // Moving to another item releases the one it leaves.
        registry.moveViewer(1)
        b.viewer.play()
        assertEquals(other, registry.openViewerSrc)
        assertTrue(players[0].released)
        assertFalse(players[1].released)
        registry.closeViewer()
        assertNull(registry.openViewerSrc)
        assertTrue(players[1].released)
        // releaseAll closes it too.
        registry.openViewer(items, 0)
        registry.releaseAll()
        assertNull(registry.openViewerSrc)
        assertTrue(registry.viewerItems.isEmpty())
    }

    @Test fun theControllerIsOnlyShownWhileItsPartOfTheBoxIsOnScreen() {
        val full = androidx.compose.ui.geometry.Rect(0f, 100f, 300f, 400f) // a 300 px tall box
        fun visible(top: Float, bottom: Float) = androidx.compose.ui.geometry.Rect(0f, top, 300f, bottom)
        val reserve = 96f
        assertTrue("whole box visible", VideoSizing.controllerVisible(full, full, reserve))
        assertTrue("top scrolled under the header, the bar's part still on screen", VideoSizing.controllerVisible(full, visible(200f, 400f), reserve))
        assertFalse("bottom under the composer", VideoSizing.controllerVisible(full, visible(100f, 350f), reserve))
        assertFalse("only a sliver of the bottom is left", VideoSizing.controllerVisible(full, visible(350f, 400f), reserve))
        assertFalse("scrolled out entirely", VideoSizing.controllerVisible(full, androidx.compose.ui.geometry.Rect.Zero, reserve))
        val short = androidx.compose.ui.geometry.Rect(0f, 100f, 300f, 160f) // shorter than the bar: the whole box must show
        assertTrue(VideoSizing.controllerVisible(short, short, reserve))
        assertFalse(VideoSizing.controllerVisible(short, visible(100f, 140f), reserve))
    }

    @Test fun theKnownSizeSurvivesReleaseSoTheIdleBoxIsNotBackToTwoToOne() {
        val players = mutableListOf<FakePlayer>()
        val registry = registry(Server(chunks = 2, gates = listOf(CompletableDeferred(), CompletableDeferred())), players)
        val clip = registry.clip(src)!!
        clip.noteSize(1280, 720)
        clip.inline.play()
        registry.releaseAll()
        assertEquals(1280 to 720, clip.knownSize)
        assertSame("the same clip object (and its size) comes back", clip, registry.clip(src))
        assertEquals(ClipState.Idle, clip.inline.state)
        assertBox(300f, 168.75f, VideoSizing.inline(clip.knownSize, 300f))
    }

    private fun assertBox(w: Float, h: Float, actual: VideoSizing.Box) {
        assertEquals(w, actual.width, 0.01f)
        assertEquals(h, actual.height, 0.01f)
    }

    @Test fun theBoxSizesAsTheWebsVideoElementDoes() {
        // Unknown: the browser's 300 x 150, or 2:1 of a narrower row.
        assertBox(300f, 150f, VideoSizing.inline(null, 400f))
        assertBox(250f, 125f, VideoSizing.inline(null, 250f))
        // Known: its own pixels, down to the row width and 320 tall, ratio kept, never up.
        assertBox(300f, 168.75f, VideoSizing.inline(1280 to 720, 300f))
        assertBox(568.89f, 320f, VideoSizing.inline(1280 to 720, 900f))
        assertBox(180f, 320f, VideoSizing.inline(720 to 1280, 900f))
        assertBox(320f, 240f, VideoSizing.inline(320 to 240, 900f))
        assertBox(300f, 150f, VideoSizing.inline(0 to 0, 900f))
        // The lightbox stage: contain, never up.
        assertBox(400f, 225f, VideoSizing.fit(1280 to 720, 400f, 800f))
        assertBox(320f, 240f, VideoSizing.fit(320 to 240, 800f, 800f))
    }
}
