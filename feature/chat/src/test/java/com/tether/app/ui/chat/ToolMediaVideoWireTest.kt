package com.tether.app.ui.chat

import com.tether.app.client.FilesAuthority
import com.tether.app.client.HttpToolMedia
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ta-2hv, ta-coik.68: a clip over the real [HttpToolMedia] (OkHttp, the paired bearer, no redirects)
 * from a replayed server. A redirect through `video()` is Blocked, never followed and never cached;
 * the streaming path asks the server ONCE, with no Range header, and caches only the finished,
 * verified clip.
 */
@RunWith(RobolectricTestRunner::class)
class ToolMediaVideoWireTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<RecordedRequest>()
    private val clip = ByteArray(8192) { (it * 5).toByte() }.also { b -> "\u0000\u0000\u0000\u0018ftypmp42".forEachIndexed { i, c -> b[i] = c.code.toByte() } }
    private val path get() = "/api/tool-media/${sha256Hex(clip)}.mp4"
    private lateinit var origin: String
    private lateinit var source: HttpToolMedia
    private var respond: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return respond(request)
            }
        }
        server.start()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val url = server.url("/")
        origin = url.toString().trimEnd('/')
        source = HttpToolMedia(http) { FilesAuthority.Paired(url) { it.header("Authorization", "Bearer tthr_device") } }
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
        Unit
    }

    private val cacheDir get() = RuntimeEnvironment.getApplication().cacheDir
    private fun ok() = MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setBody(Buffer().write(clip))

    @Test fun aRedirectThroughVideoIsBlockedNeverFollowedAndNeverCached() = runBlocking {
        respond = { r ->
            if (r.path == path) MockResponse().setResponseCode(302).setHeader("Location", "https://sso.example.test/login") else MockResponse().setResponseCode(404)
        }
        val repo = ToolMediaRepository(source, cacheDir, origin)
        assertEquals(MediaVideo.Blocked, repo.video(ToolMediaItem("video", "video/mp4", path)))
        assertEquals("the redirect was not followed: one request, to the paired origin", listOf(path), seen.map { it.path })
        assertTrue("nothing cached, no partial left", ToolMediaCache.dirFor(cacheDir, origin).list().isNullOrEmpty())
    }

    @Test fun aSignInPageAnswerIsBlockedToo() = runBlocking {
        respond = { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>sign in</html>") }
        val repo = ToolMediaRepository(source, cacheDir, origin)
        assertEquals(MediaVideo.Blocked, repo.video(ToolMediaItem("video", "video/mp4", path)))
    }

    @Test fun theStreamingPathIsOneGetWithTheCredentialAndNoRangeAndCachesTheVerifiedClip() = runBlocking {
        respond = { ok() }
        val download = ClipDownload(source, cacheDir, origin, path, sha256Hex(clip))
        download.start()
        val reader = ClipReader(download)
        assertTrue(reader.open())
        val outcome = run {
            val until = System.nanoTime() + 20_000_000_000L
            while (download.outcome() == null && System.nanoTime() < until) Thread.sleep(10)
            download.outcome()
        }
        assertTrue("verified: $outcome", outcome is MediaVideo.Ok)
        val out = ByteArray(100)
        assertEquals(100, reader.readAt(4000, out, 0, 100))
        assertTrue(out.contentEquals(clip.copyOfRange(4000, 4100)))
        assertEquals(clip.size.toLong(), reader.size)
        reader.close()
        synchronized(seen) {
            assertEquals("one GET for the whole clip", 1, seen.size)
            assertEquals("GET", seen[0].method)
            assertEquals(path, seen[0].path)
            assertNull("no Range: the server never serves one", seen[0].getHeader("Range"))
            assertEquals("Bearer tthr_device", seen[0].getHeader("Authorization"))
        }
        assertEquals(listOf("${sha256Hex(clip)}.mp4"), ToolMediaCache.dirFor(cacheDir, origin).list()!!.toList())
        // The next play: from the cache, no GET.
        val again = ClipDownload(source, cacheDir, origin, path, sha256Hex(clip))
        again.start()
        assertTrue(ClipReader(again).open())
        assertEquals(1, synchronized(seen) { seen.size })
    }
}
