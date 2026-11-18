package com.tether.app.client

import com.tether.app.protocol.TetherJson
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The `POST /api/files/exists` request shape and the answers the client accepts. */
class ExistsBatchesTest {
    private val server = MockWebServer()
    private lateinit var files: HttpWorkspaceFiles

    @Before fun setUp() {
        server.start()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val authority = FilesAuthority.Paired(server.url("/")) { it.header("Authorization", "Bearer tthr_test") }
        files = HttpWorkspaceFiles(http, { authority })
    }

    @After fun tearDown() = server.shutdown()

    @Test fun aBodyAtTheLimitIsOneChunkAndOneOverIsTwo() {
        val a = "/" + "x".repeat(2000)
        val b = "/" + "y".repeat(2000)
        val one = ExistsBatches.split(listOf(a, b))
        assertEquals(1, one.chunks.size)
        assertTrue(ExistsBatches.body(one.chunks[0]).length <= ExistsBatches.MAX_BODY)
        val two = ExistsBatches.split(listOf(a, b, "/" + "z".repeat(200)))
        assertEquals(2, two.chunks.size)
        assertEquals(listOf(a, b, "/" + "z".repeat(200)), two.chunks.flatten())
    }

    @Test fun nonAsciiPathsAreSizedByTheirJsonLength() {
        val paths = (0 until 40).map { "/w/" + "é中😀".repeat(50) + it }
        val split = ExistsBatches.split(paths)
        assertTrue(split.chunks.size > 1)
        assertEquals(paths, split.chunks.flatten())
        for (chunk in split.chunks) assertTrue(ExistsBatches.body(chunk).length <= ExistsBatches.MAX_BODY)
    }

    @Test fun countIsCappedAtTwoHundred() {
        val split = ExistsBatches.split((0 until 401).map { "/a/$it" })
        assertEquals(listOf(200, 200, 1), split.chunks.map { it.size })
    }

    @Test fun aPathThatAloneBreaksTheLimitIsNotSent() {
        val long = "/" + "x".repeat(ExistsBatches.MAX_BODY)
        val split = ExistsBatches.split(listOf("/a/b", long, "/c/d"))
        assertEquals(listOf(long), split.unsendable)
        assertEquals(listOf(listOf("/a/b", "/c/d")), split.chunks)
    }

    @Test fun existsPostsTheBodyAndReadsEveryAnswerInOrder() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"exists":[true,false,"yes"]}"""))
        val result = files.exists(listOf("/a/é.kt", "/b.kt", "/c.kt")) as FilesResult.Ok
        assertEquals(listOf(true, false, null), result.value)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/api/files/exists", req.path)
        assertEquals("Bearer tthr_test", req.getHeader("Authorization"))
        val body = TetherJson.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals(listOf("/a/é.kt", "/b.kt", "/c.kt"), body["paths"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun everyOtherAnswerIsAFailure() = runBlocking {
        val answers = listOf(
            MockResponse().setResponseCode(404).setBody("""{"error":"Not found"}"""),
            MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden"}"""),
            MockResponse().setResponseCode(401).setBody("nope"),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"),
            MockResponse().setResponseCode(200).setBody("""{"exists":[true]}"""), // wrong length for two paths
            MockResponse().setResponseCode(200).setBody("""{"other":[true,true]}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )
        for (answer in answers) {
            server.enqueue(answer)
            val result = files.exists(listOf("/a.kt", "/b.kt"))
            assertTrue(result.toString(), result is FilesResult.Failed)
        }
    }
}
