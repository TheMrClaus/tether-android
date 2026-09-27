package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * T11.1 request shapes: every /api/files op against a MockWebServer "Tether", matching the web's
 * fetch calls (workspace-file-browser.tsx) and the server's route contracts (server.mjs
 * 7152-7390). The credential here is a stand-in bearer; [WorkspaceFilesClientTest] proves the
 * real client's own credentials reach the same routes.
 */
class WorkspaceFilesHttpTest {
    private val server = MockWebServer()
    private val elsewhere = MockWebServer()
    private val noRedirects = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private var authority: FilesAuthority = FilesAuthority.SignedOut
    private lateinit var files: HttpWorkspaceFiles

    @Before fun setUp() {
        server.start()
        elsewhere.start()
        authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        files = HttpWorkspaceFiles(noRedirects, { authority }, uploadCap = 64 * 1024, listingCap = 4 * 1024)
    }

    @After fun tearDown() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request reached the server")

    private fun json(request: RecordedRequest) = TetherJson.parseToJsonElement(request.body.readUtf8()) as JsonObject

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private fun err(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private val odd = "/w/a b+c&d#e%f/é😀?x=y"
    private val oddEncoded = "%2Fw%2Fa%20b%2Bc%26d%23e%25f%2F%C3%A9%F0%9F%98%80%3Fx%3Dy"

    @Test fun encodeUriComponentMatchesTheWeb() {
        // Node: encodeURIComponent("a b+c&d#e%f/é😀!'()*~-_.") — the unreserved marks stay literal.
        assertEquals("a%20b%2Bc%26d%23e%25f%2F%C3%A9%F0%9F%98%80!'()*~-_.", HttpWorkspaceFiles.encodeUriComponent("a b+c&d#e%f/é😀!'()*~-_."))
    }

    @Test fun listSendsTheEncodedPathAndKeepsTheServersOrder() = runBlocking {
        server.enqueue(
            ok(
                """{"current":"/w/app","parent":"/w","breadcrumbs":[{"name":"/","path":"/"},{"name":"w","path":"/w"},{"name":"app","path":"/w/app"}],
                "entries":[{"name":"src","path":"/w/app/src","size":4096,"mtime":1767225600000.5,"isDirectory":true},
                           {"name":"b.txt","path":"/w/app/b.txt","size":3,"mtime":1767225600000,"isDirectory":false},
                           {"name":"a.txt","path":"/w/app/a.txt","size":65,"mtime":1767225600000,"isDirectory":false},
                           {"path":"/w/app/nameless"},{"name":7,"path":"/w/app/x"}]}""",
            ),
        )
        val result = files.list(odd) as FilesResult.Ok
        val req = take()
        assertEquals("GET", req.method)
        assertEquals("/api/files/list?path=$oddEncoded", req.path)
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        val listing = result.value
        assertEquals("/w/app", listing.current)
        assertEquals("/w", listing.parent)
        assertEquals(listOf("/", "w", "app"), listing.breadcrumbs.map { it.name })
        // Rendered in the order the server sends (folders first, then localeCompare); entries
        // without a string name/path are dropped, never guessed.
        assertEquals(listOf("src", "b.txt", "a.txt"), listing.entries.map { it.name })
        assertEquals(WorkspaceFileEntry("src", "/w/app/src", 4096, 1767225600000.5, true), listing.entries[0])
    }

    @Test fun listErrorsCarryTheServersCopyOrTheWebFallback() = runBlocking {
        server.enqueue(err(403, """{"error":"That path is outside the configured allowed roots."}"""))
        assertEquals(FilesResult.Failed("That path is outside the configured allowed roots.", 403), files.list("/etc"))
        server.enqueue(err(502, "<html>Bad gateway</html>"))
        assertEquals(FilesResult.Failed("This folder could not be opened.", 502), files.list("/w"))
        server.enqueue(ok("""{"entries":[]}"""))
        assertEquals(FilesResult.Failed("This folder could not be opened.", 200), files.list("/w"))
    }

    @Test fun anOversizedListingIsRefusedNotParsed() = runBlocking {
        val huge = """{"current":"/w","parent":null,"breadcrumbs":[],"entries":[""" +
            (1..400).joinToString(",") { """{"name":"f$it","path":"/w/f$it","size":1,"mtime":1,"isDirectory":false}""" } + "]}"
        server.enqueue(ok(huge))
        assertEquals(FilesResult.Failed("This folder could not be opened.", 200), files.list("/w"))
    }

    @Test fun mkdirTouchRenamePostTheWebsJsonBodies() = runBlocking {
        server.enqueue(ok("""{"ok":true,"parent":"/w"}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w")), files.mkdir("/w", "new dir"))
        take().let { req ->
            assertEquals("POST", req.method)
            assertEquals("/api/files/mkdir", req.path)
            assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals("application/json", req.getHeader("Accept"))
            assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
            val body = json(req)
            assertEquals(setOf("path", "name"), body.keys)
            assertEquals("/w", body["path"]!!.jsonPrimitive.content)
            assertEquals("new dir", body["name"]!!.jsonPrimitive.content)
        }

        server.enqueue(ok("""{"ok":true,"parent":"/w","path":"/w/notes.md","size":0}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w", "/w/notes.md", 0)), files.touch("/w", "notes.md"))
        take().let { req ->
            assertEquals("/api/files/touch", req.path)
            val body = json(req)
            assertEquals("/w", body["path"]!!.jsonPrimitive.content)
            assertEquals("notes.md", body["name"]!!.jsonPrimitive.content)
        }

        server.enqueue(ok("""{"ok":true,"parent":"/w","path":"/w/b.md"}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w", "/w/b.md")), files.rename("/w/a.md", "b.md"))
        take().let { req ->
            assertEquals("/api/files/rename", req.path)
            val body = json(req)
            assertEquals("/w/a.md", body["path"]!!.jsonPrimitive.content)
            assertEquals("b.md", body["name"]!!.jsonPrimitive.content)
        }
    }

    @Test fun moveAndCopySendPathAndDestination() = runBlocking {
        server.enqueue(ok("""{"ok":true,"parent":"/w/docs","path":"/w/docs/a.md"}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w/docs", "/w/docs/a.md")), files.move("/w/a.md", "/w/docs"))
        take().let { req ->
            assertEquals("POST", req.method)
            assertEquals("/api/files/move", req.path)
            val body = json(req)
            assertEquals(setOf("path", "destination"), body.keys)
            assertEquals("/w/a.md", body["path"]!!.jsonPrimitive.content)
            assertEquals("/w/docs", body["destination"]!!.jsonPrimitive.content)
        }
        server.enqueue(ok("""{"ok":true,"parent":"/w/docs","path":"/w/docs/a.md"}"""))
        files.copy("/w/a.md", "/w/docs")
        take().let { req ->
            assertEquals("/api/files/copy", req.path)
            assertEquals("/w/docs", json(req)["destination"]!!.jsonPrimitive.content)
        }
    }

    @Test fun mutationErrorsCarryTheServersCopy() = runBlocking {
        server.enqueue(err(409, """{"error":"An item with that name already exists here."}"""))
        assertEquals(FilesResult.Failed("An item with that name already exists here.", 409), files.mkdir("/w", "src"))
        server.enqueue(err(400, """{"error":"A folder cannot be moved or copied into itself or one of its own subfolders."}"""))
        assertEquals(
            FilesResult.Failed("A folder cannot be moved or copied into itself or one of its own subfolders.", 400),
            files.move("/w/src", "/w/src/deep"),
        )
        // Non-JSON / non-string error: the web's per-op fallback.
        server.enqueue(err(500, """{"error":42}"""))
        assertEquals(FilesResult.Failed("That item could not be renamed.", 500), files.rename("/w/a", "b"))
        server.enqueue(err(504, "gateway timeout"))
        assertEquals(FilesResult.Failed("That action could not be completed.", 504), files.copy("/w/a", "/w/b"))
    }

    @Test fun deleteIsADeleteOnTheFileRoute() = runBlocking {
        server.enqueue(ok("""{"ok":true,"parent":"/w"}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w")), files.delete(odd))
        val req = take()
        assertEquals("DELETE", req.method)
        assertEquals("/api/files?path=$oddEncoded", req.path)
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals(0L, req.bodySize)
        server.enqueue(err(404, """{"error":"That item is not available."}"""))
        assertEquals(FilesResult.Failed("That item is not available.", 404), files.delete("/w/gone"))
    }

    private class Bytes(private val bytes: ByteArray, override val length: Long? = bytes.size.toLong()) : UploadSource {
        var opens = 0
        override fun open(): InputStream {
            opens++
            return ByteArrayInputStream(bytes)
        }
    }

    @Test fun uploadStreamsTheRawBytesAsAPut() = runBlocking {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val source = Bytes(bytes)
        server.enqueue(ok("""{"ok":true,"parent":"/w","path":"/w/photo 1.png","size":20000}"""))
        assertEquals(FilesResult.Ok(WorkspaceMutation("/w", "/w/photo 1.png", 20_000)), files.upload("/w", "photo 1.png", source))
        val req = take()
        assertEquals("PUT", req.method)
        assertEquals("/api/files/upload?path=%2Fw&name=photo%201.png", req.path)
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("20000", req.getHeader("Content-Length"))
        assertTrue(bytes.contentEquals(req.body.readByteArray()))
        assertEquals(1, source.opens)
    }

    @Test fun uploadOverwriteAndUnknownLengthStreamChunked() = runBlocking {
        server.enqueue(ok("""{"ok":true,"parent":"/w"}"""))
        files.upload("/w", "a.bin", Bytes(ByteArray(10) { 1 }, length = null), overwrite = true)
        val req = take()
        assertEquals("/api/files/upload?path=%2Fw&name=a.bin&overwrite=1", req.path)
        assertNull(req.getHeader("Content-Length"))
        assertEquals("chunked", req.getHeader("Transfer-Encoding"))
        assertEquals(10L, req.bodySize)
    }

    @Test fun uploadErrorsCarryTheServersCopy() = runBlocking {
        server.enqueue(err(409, """{"error":"An item with that name already exists here."}"""))
        assertEquals(FilesResult.Failed("An item with that name already exists here.", 409), files.upload("/w", "a", Bytes(ByteArray(3))))
        server.enqueue(err(413, """{"error":"Uploads are limited to 512 MB."}"""))
        assertEquals(FilesResult.Failed("Uploads are limited to 512 MB.", 413, tooLarge = true), files.upload("/w", "a", Bytes(ByteArray(3))))
        server.enqueue(err(500, "nope"))
        assertEquals(FilesResult.Failed("\"a b\" could not be uploaded.", 500), files.upload("/w", "a b", Bytes(ByteArray(3))))
    }

    @Test fun anUploadOverTheCapIsRefusedLocallyBeforeAnyByte() = runBlocking {
        val source = Bytes(ByteArray(64 * 1024 + 1))
        assertEquals(FilesResult.Failed("Uploads are limited to 512 MB.", tooLarge = true), files.upload("/w", "big", source))
        assertEquals(0, source.opens)
        assertEquals(0, server.requestCount)
    }

    @Test fun anUploadThatGrowsPastTheCapWhileStreamingIsCutOff() = runBlocking {
        // Unknown length (a provider that reports none): the stream is counted and cut off.
        server.enqueue(ok("""{"ok":true,"parent":"/w"}"""))
        val result = files.upload("/w", "big", Bytes(ByteArray(200 * 1024), length = null))
        assertEquals(FilesResult.Failed("Uploads are limited to 512 MB.", tooLarge = true), result)
    }

    @Test fun headReportsTheCurrentLength() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", "123").setHeader("Content-Type", "image/png"))
        assertEquals(FilesResult.Ok(FileHead(123, "image/png")), files.head("/w/a.png"))
        val req = take()
        assertEquals("HEAD", req.method)
        assertEquals("/api/files?path=%2Fw%2Fa.png", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
    }

    @Test fun textPreviewAsksForTheFirstMegabyteAndReadsNoMore() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody("# hello\n"))
        assertEquals(FilesResult.Ok("# hello\n"), files.readText("/w/README.md", listedSize = 8))
        take().let { req ->
            assertEquals("GET", req.method)
            assertEquals("/api/files?path=%2Fw%2FREADME.md", req.path)
            assertEquals("text/plain", req.getHeader("Accept"))
            assertEquals("bytes=0-1048575", req.getHeader("Range"))
        }
        // An empty file is fetched without a Range (the server answers 416 to a range on 0 bytes).
        server.enqueue(MockResponse().setBody(""))
        assertEquals(FilesResult.Ok(""), files.readText("/w/empty.txt", listedSize = 0))
        assertNull(take().getHeader("Range"))
        // A server (or proxy) that ignores the Range still yields at most 1 MiB.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(3 * 1024 * 1024) { 'x'.code.toByte() })))
        val text = (files.readText("/w/grew.log", listedSize = 10) as FilesResult.Ok).value
        assertEquals(1024 * 1024, text.length)
        take()
        // The web never shows an error body here.
        server.enqueue(err(404, """{"error":"That file is not available."}"""))
        assertEquals(FilesResult.Failed("This text file could not be opened.", 404), files.readText("/w/gone.txt", 3))
    }

    @Test fun downloadStreamsIntoTheSinkUnderTheCap() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val sink = ByteArrayOutputStream()
        assertEquals(FilesResult.Ok(300_000L), files.download("/w/a.png", 1_000_000, sink))
        assertTrue(bytes.contentEquals(sink.toByteArray()))
        assertEquals("/api/files?path=%2Fw%2Fa.png", take().path)
    }

    @Test fun downloadOverTheCapIsRefusedByLengthOrCutOffWhileStreaming() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2_000))))
        val declared = ByteArrayOutputStream()
        assertEquals(FilesResult.Failed("This file is too large to open here.", 200, tooLarge = true), files.download("/w/big", 1_000, declared))
        assertEquals("nothing is written when the declared length is over", 0, declared.size())
        take()
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(500_000)), 4_096))
        val streamed = ByteArrayOutputStream()
        assertEquals(FilesResult.Failed("This file is too large to open here.", 200, tooLarge = true), files.download("/w/big", 100_000, streamed))
        assertTrue("stopped at the cap, not after the whole body", streamed.size() <= 100_000)
    }

    @Test fun aRedirectIsNeverFollowedWithTheCredential() = runBlocking {
        // Whoever answers with a redirect does not get to put words in the UI either.
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/api/files/list?path=%2F"))
                .setBody("""{"error":"Session expired: sign in again at the link"}"""),
        )
        assertEquals(FilesResult.Failed("This folder could not be opened.", 302), files.list("/w"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/api/files/mkdir")))
        assertEquals(FilesResult.Failed("That folder could not be created.", 307), files.mkdir("/w", "x"))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", elsewhere.url("/steal")))
        assertEquals(FilesResult.Failed("This file could not be opened.", 301), files.download("/w/a", 10, ByteArrayOutputStream()))
        assertEquals("the other origin saw nothing", 0, elsewhere.requestCount)
    }

    @Test fun aClientThatFollowsRedirectsIsRejected() {
        try {
            HttpWorkspaceFiles(OkHttpClient(), authority = { authority })
            fail("a redirect-following client must not carry the credential")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun signedOutOrBlockedCallsNeverTouchTheNetwork() = runBlocking {
        authority = FilesAuthority.SignedOut
        assertEquals(FilesResult.Failed("Sign in to browse workspace files."), files.list("/w"))
        authority = FilesAuthority.LocalNetworkBlocked
        assertEquals(FilesResult.Failed("Local network access is blocked."), files.delete("/w/a"))
        assertEquals(0, server.requestCount)
    }

    @Test fun aTransportFailureIsReportedNotThrown() = runBlocking {
        server.shutdown()
        assertEquals(FilesResult.Failed("The server could not be reached."), files.list("/w"))
    }

    @Test fun cancellingTheCallerCancelsTheTransfer() = runBlocking {
        // A body the server would take ~20 s to send: cancelling must end the call at once.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(200_000))).throttleBody(10_000, 1, TimeUnit.SECONDS))
        val job = async(Dispatchers.Default) { files.download("/w/slow", 10_000_000, ByteArrayOutputStream()) }
        take()
        delay(200)
        withTimeout(3_000) {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
    }
}
