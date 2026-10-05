package com.tether.app.ui.setup

import com.tether.app.client.HttpSetupApi
import com.tether.app.client.SetupCall
import com.tether.app.client.SetupCopy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10.6: [HttpSetupApi] against a fake first-run server shaped from setup-server.mjs ([SetupServer]); no
 * real server. Request shapes (every POST JSON, bodiless ones too, no Origin / credential), and the replies
 * as page.tsx reads them.
 */
class SetupApiTest {
    private val fake = SetupServer()
    private val web = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val api = HttpSetupApi(http, web.url("/"))

    @After fun tearDown() = web.shutdown()

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test fun stateIsReadWithNoCredentialAndNoOrigin() {
        val call = run { api.state() } as SetupCall.Ok
        val state = call.value
        assertEquals("native", state.runtime)
        assertEquals(listOf("fake", "claude", "codex", "opencode", "reasonix", "pi", "acp", "dsh"), state.supportedModes)
        assertEquals("/home/op", state.defaultFolder)
        assertFalse(state.forced("password"))
        val seen = fake.calls("/api/setup/state").single()
        assertEquals("GET", seen.method)
        assertNull(seen.cookie)
        assertNull(seen.authorization)
        assertNull(seen.origin)
    }

    @Test fun aReplyThatIsNotAStateDocumentIsNotAnAnswer() {
        fake.state = """{"ok":true}"""
        val call = run { api.state() } as SetupCall.Failed
        assertEquals(SetupCopy.STATE_FAILED, call.message)
        fake.state = "<html>gateway</html>"
        assertEquals(SetupCopy.STATE_FAILED, (run { api.state() } as SetupCall.Failed).message)
    }

    @Test fun aSetupRouteAfterCompletionIs401() {
        fake.configured = true
        val call = run { api.state() } as SetupCall.Failed
        assertEquals(401, call.status)
    }

    @Test fun detectReadsEachEngine() {
        val call = run { api.detect() } as SetupCall.Ok
        assertTrue(call.value.getValue("claude").found)
        assertEquals("host install", call.value.getValue("claude").source)
        assertEquals("/usr/bin/codex", call.value.getValue("codex").binPath)
        assertFalse(call.value.getValue("opencode").found)
    }

    @Test fun browseSendsThePathAndReadsTheListing() {
        val call = run { api.browse("/home/op/work space") } as SetupCall.Ok
        assertEquals("/home/op/work space", call.value.current)
        assertEquals("/home/op", call.value.parent)
        assertEquals(listOf("projects", "work"), call.value.entries.map { it.name })
        assertEquals("/api/setup/browse?path=%2Fhome%2Fop%2Fwork%20space", fake.seen.last().target.replace("+", "%20"))
        // No path: the server's own default.
        run { api.browse(null) }
        assertEquals("/api/setup/browse", fake.seen.last().target)
    }

    @Test fun browseFailureCarriesTheServersSentenceElseThePages() {
        fake.browse = { SetupServer.json(400, """{"ok":false,"error":"ENOENT: no such directory"}""") }
        assertEquals("ENOENT: no such directory", (run { api.browse("/nope") } as SetupCall.Failed).message)
        fake.browse = { SetupServer.json(400, """{}""") }
        assertEquals(SetupCopy.BROWSE_FAILED, (run { api.browse("/nope") } as SetupCall.Failed).message)
    }

    @Test fun validateIsAJsonPostWithTheEngineAndTheValue() {
        val call = run { api.validateEngineBinary("opencode", "/opt/oc") } as SetupCall.Ok
        assertTrue(call.value.ok)
        assertEquals("Found here.", call.value.message)
        val seen = fake.calls("/api/setup/validate").single()
        assertEquals("POST", seen.method)
        assertTrue(seen.contentType!!.startsWith("application/json"))
        assertNull(seen.origin)
        assertEquals(buildJsonObject { put("kind", "engineBinary"); put("engine", "opencode"); put("value", "/opt/oc") }.toString(), seen.body)
    }

    @Test fun completeSendsTheSettingsAsJsonAndNothingElse() {
        val settings = buildJsonObject {
            put("username", "op")
            put("password", "s3cret pass")
            put("headlessModes", "claude")
        }
        val call = run { api.complete(settings) } as SetupCall.Ok
        assertEquals("manual", call.value.restart)
        assertFalse(call.value.automatic)
        val seen = fake.calls("/api/setup/complete").single()
        assertTrue(seen.contentType!!.startsWith("application/json"))
        assertNull(seen.origin)
        assertNull(seen.cookie)
        assertNull(seen.authorization)
        assertEquals(buildJsonObject { put("settings", settings) }.toString(), seen.body)
        // After completion the fake server is the normal one.
        assertEquals(401, (run { api.state() } as SetupCall.Failed).status)
    }

    @Test fun aBodilessPostIsStillJson() {
        // Part 2's routes (github login, a Claude account's login) are bodiless: the guard still wants JSON.
        val sent = run { api.post("/api/setup/github/login", null) } as HttpSetupApi.Sent.Got
        // The fake has no such route (404), which is past the guard (403 would be the refusal).
        assertEquals(404, sent.reply.status)
        val seen = fake.calls("/api/setup/github/login").single()
        assertTrue(seen.contentType!!.startsWith("application/json"))
        assertEquals("", seen.body)
    }

    @Test fun theFakeRefusesAWriteThatIsNotJson() {
        // The negative control: this is what a text/plain POST gets, so the passes above mean something.
        val request = Request.Builder().url(web.url("/api/setup/complete")).post("{}".toRequestBody("text/plain".toMediaType())).build()
        http.newCall(request).execute().use { assertEquals(403, it.code) }
        assertTrue(fake.calls("/api/setup/complete").size == 1)
    }

    @Test fun completeFailureReadsAsThePageReadsIt() {
        fake.complete = { SetupServer.json(400, """{"ok":false,"errors":[{"key":"username","message":"username must not be empty."}]}""") }
        assertEquals("username must not be empty.", (run { api.complete(JsonObject(emptyMap())) } as SetupCall.Failed).message)
        fake.complete = { SetupServer.json(400, """{"ok":false,"problems":[{"key":"a","message":"One."},{"key":"b","message":"Two."}]}""") }
        assertEquals("One. Two.", (run { api.complete(JsonObject(emptyMap())) } as SetupCall.Failed).message)
        fake.complete = { SetupServer.json(429, """{"error":"Too many setup completion attempts. Wait and retry."}""") }
        val limited = run { api.complete(JsonObject(emptyMap())) } as SetupCall.Failed
        assertEquals(429, limited.status)
        assertEquals("Too many setup completion attempts. Wait and retry.", limited.message)
        fake.complete = { SetupServer.json(500, """{}""") }
        assertEquals(SetupCopy.COMPLETE_FAILED, (run { api.complete(JsonObject(emptyMap())) } as SetupCall.Failed).message)
    }

    @Test fun configuredIsTrueOnceHealthzStopsSayingSetupRequired() {
        assertFalse(run { api.configured() })
        fake.configured = true
        assertTrue(run { api.configured() })
        // A server that is down (bouncing) is not yet configured.
        web.shutdown()
        assertFalse(run { api.configured() })
    }

    @Test fun aClientThatFollowsRedirectsIsRefused() {
        val follows = OkHttpClient()
        val refused = runCatching { HttpSetupApi(follows, web.url("/")) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }
}
