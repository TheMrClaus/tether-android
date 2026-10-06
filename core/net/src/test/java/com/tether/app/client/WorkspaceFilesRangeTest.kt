package com.tether.app.client

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ta-1u4: [WorkspaceFiles.readRange], the video player's data path. Same trust rules as every
 * other /api/files read: the paired origin and credential per call, no redirect ever followed,
 * and a pinned read refuses to leave the server it started on. Answers follow
 * lib/workspace-file-http.mjs (206 + Content-Range for a satisfiable single range, 416 with
 * `bytes * / total` past the end).
 */
class WorkspaceFilesRangeTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var files: HttpWorkspaceFiles

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        files = HttpWorkspaceFiles(noRedirects, { authority })
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun partial(body: ByteArray, start: Long, total: Long) = MockResponse()
        .setResponseCode(206)
        .setHeader("Accept-Ranges", "bytes")
        .setHeader("Content-Range", "bytes $start-${start + body.size - 1}/$total")
        .setBody(Buffer().write(body))

    @Test fun aRangeReadAsksForExactlyThatRangeAndGetsThe206Bytes() = runBlocking {
        server.enqueue(partial(byteArrayOf(1, 2, 3, 4), start = 100, total = 5000))
        val read = (files.readRange("/w/a b.mp4", 100, 4) as FilesResult.Ok).value
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/files?path=%2Fw%2Fa%20b.mp4", req.path)
        assertEquals("bytes=100-103", req.getHeader("Range"))
        assertEquals("a compressed answer would not be the range", "identity", req.getHeader("Accept-Encoding"))
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read.bytes)
        assertEquals(5000L, read.total)
        assertEquals(server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }, read.origin)
    }

    @Test fun aShortFinalRangeIsReturnedAsIs() = runBlocking {
        server.enqueue(partial(byteArrayOf(9, 9), start = 4998, total = 5000))
        val read = (files.readRange("/w/a.mp4", 4998, 65536) as FilesResult.Ok).value
        assertEquals("bytes=4998-70533", take().getHeader("Range"))
        assertEquals(2, read.bytes.size)
        assertEquals(5000L, read.total)
    }

    @Test fun anUnknownTotalIsNullNotGuessed() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-1/*").setBody(Buffer().write(byteArrayOf(5, 6))),
        )
        val read = (files.readRange("/w/a.mp4", 0, 2) as FilesResult.Ok).value
        assertNull(read.total)
        assertEquals(2, read.bytes.size)
    }

    @Test fun aServerThatIgnoresTheRangeIsReadOnlyForItsFirstBytes() = runBlocking {
        // 200 + the whole body: only [length] bytes are taken (the rest is never read).
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10_000) { (it % 251).toByte() })))
        val read = (files.readRange("/w/a.mp4", 0, 16) as FilesResult.Ok).value
        assertEquals(16, read.bytes.size)
        assertEquals(10_000L, read.total)
        assertEquals(0, read.bytes[0].toInt())
        assertEquals(15, read.bytes[15].toInt())
    }

    @Test fun aServerThatIgnoresTheRangeCannotServeAReadFromFurtherIn() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10_000))))
        assertEquals(FilesResult.Failed("This file could not be opened.", 200), files.readRange("/w/a.mp4", 5_000, 16))
    }

    @Test fun anAnswerForAnotherRangeIsRefused() = runBlocking {
        server.enqueue(partial(byteArrayOf(1, 2), start = 0, total = 100))
        assertEquals(FilesResult.Failed("This file could not be opened.", 206), files.readRange("/w/a.mp4", 50, 2))
        server.enqueue(MockResponse().setResponseCode(206).setBody("no content-range"))
        assertEquals(FilesResult.Failed("This file could not be opened.", 206), files.readRange("/w/a.mp4", 0, 2))
    }

    @Test fun readingPastTheEndIsEmptyWithTheLength() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */5000"))
        val read = (files.readRange("/w/a.mp4", 9000, 100) as FilesResult.Ok).value
        assertEquals(0, read.bytes.size)
        assertEquals(5000L, read.total)
        // An empty file answers every range with 416 and `bytes */0`.
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */0"))
        assertEquals(0L, ((files.readRange("/w/empty.mp4", 0, 100)) as FilesResult.Ok).value.total)
        // A 416 that does not say the length is a failure, not an empty file.
        server.enqueue(MockResponse().setResponseCode(416))
        assertEquals(FilesResult.Failed("This file could not be opened.", 416), files.readRange("/w/a.mp4", 9000, 100))
    }

    @Test fun errorsAreTheFileFallbackAndNeverTheServersBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"secret detail"}"""))
        assertEquals(FilesResult.Failed("This file could not be opened.", 404), files.readRange("/w/a.mp4", 0, 10))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(FilesResult.Failed("This file could not be opened.", 401), files.readRange("/w/a.mp4", 0, 10))
    }

    @Test fun badArgumentsFailWithoutTouchingTheNetwork() = runBlocking {
        assertTrue(files.readRange("/w/a.mp4", -1, 10) is FilesResult.Failed)
        assertTrue(files.readRange("/w/a.mp4", 0, 0) is FilesResult.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test fun aRedirectIsNeverFollowedAndNothingElsewhereIsReached() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/api/files?path=%2Fw%2Fa.mp4")))
        assertEquals(FilesResult.Failed("This file could not be opened.", 302), files.readRange("/w/a.mp4", 0, 10))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/steal")))
        assertEquals(FilesResult.Failed("This file could not be opened.", 307), files.readRange("/w/a.mp4", 0, 10))
        assertEquals("the other origin saw nothing (no credential, no request)", 0, elsewhere.requestCount)
    }

    @Test fun aRequestThatWouldLeaveThePairedOriginIsRefusedBeforeItIsSent() = runBlocking {
        authority = FilesAuthority.Paired(server.url("/")) { it.url(elsewhere.url("/api/files?path=%2F")).header("Authorization", "Bearer tthr_test") }
        assertEquals(FilesResult.Failed("This file could not be opened."), files.readRange("/w/a.mp4", 0, 10))
        assertEquals(0, elsewhere.requestCount)
        assertEquals(0, server.requestCount)
    }

    @Test fun theCredentialAndOriginAreReadAfreshForEveryRead() = runBlocking {
        server.enqueue(partial(byteArrayOf(1), 0, 10))
        files.readRange("/w/a.mp4", 0, 1)
        assertEquals("Bearer tthr_test", take().getHeader("Authorization"))
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_rotated") }
        server.enqueue(partial(byteArrayOf(2), 1, 10))
        files.readRange("/w/a.mp4", 1, 1)
        assertEquals("Bearer tthr_rotated", take().getHeader("Authorization"))
    }

    @Test fun aPinnedReadStaysOnTheServerItStartedOn() = runBlocking {
        server.enqueue(partial(byteArrayOf(1, 2), 0, 10))
        val first = (files.readRange("/w/a.mp4", 0, 2) as FilesResult.Ok).value
        take()
        // Same server: allowed.
        server.enqueue(partial(byteArrayOf(3, 4), 2, 10))
        assertTrue(files.readRange("/w/a.mp4", 2, 2, first.origin) is FilesResult.Ok)
        take()
        // The client is now signed in to another server: nothing is sent, neither server sees a request.
        authority = FilesAuthority.Paired(elsewhere.url("/")) { it.header("Authorization", "Bearer tthr_other") }
        assertEquals(FilesResult.Failed("This file could not be opened."), files.readRange("/w/a.mp4", 4, 2, first.origin))
        assertEquals(0, elsewhere.requestCount)
        assertEquals(2, server.requestCount)
        // Signed out mid-playback: the pinned read fails the way every read does.
        authority = FilesAuthority.SignedOut
        assertEquals(FilesResult.Failed("Sign in to browse workspace files."), files.readRange("/w/a.mp4", 4, 2, first.origin))
    }

    @Test fun anUnpinnedReadFollowsThePairedServerAsEveryOtherReadDoes() = runBlocking {
        authority = FilesAuthority.Paired(elsewhere.url("/")) { it.header("Authorization", "Bearer tthr_other") }
        elsewhere.enqueue(partial(byteArrayOf(1), 0, 1))
        assertTrue(files.readRange("/w/a.mp4", 0, 1) is FilesResult.Ok)
    }

    @Test fun anInterfaceImplementationWithoutRangesStillCompilesAndFailsTheRead() = runBlocking {
        // The default body: the fakes that do not know about ranges (tests elsewhere) fail the read.
        assertTrue(WorkspaceFiles.Unavailable.readRange("/w/a.mp4", 0, 1) is FilesResult.Failed)
    }
}
