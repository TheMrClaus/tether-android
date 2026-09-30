package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tether.app.client.FilesAuthority
import com.tether.app.client.HttpToolMedia
import com.tether.app.protocol.fold.reduce
import com.tether.app.protocol.model.LegacyProjectionAdapter
import com.tether.app.protocol.reduce.freshTree
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsObj
import com.tether.app.ui.theme.TetherSkin
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6.8: a tool screenshot from the wire to the screen. `tool-media-wire/events.json` is the event
 * stream an isolated fake-engine server at the production commit (protocol v135) sent for a turn
 * whose browser tool returned a base64 PNG and whose sub-agent read the same picture: the server
 * journals both as `media_ref` blocks. Folded and drawn by the real transcript, the picture comes
 * over the real [HttpToolMedia] (OkHttp, the paired bearer, no redirects) from a server replaying
 * the route's captured response, is hash-checked and decoded, and shows at its own size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ToolMediaWireRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.shutdown() }
        Unit
    }

    /** The PNG the tool returned (96 × 60), as the route served it. */
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAAA8CAIAAAAWtijjAAAAdElEQVR4nO3QsQmAAAAEsRcV99/YCSyFKwKZIMe2eydfrj3nxidBggQJEhQmSJAgQYLCBAkSJEhQmCBBggQJChMkSJAgQWGCBAkSJChMkCBBggSFCRIkSJCgMEGCBAkSFCZIkCBBgsIECRIkSFCYIEGCfvUCjWpH4ylRax0AAAAASUVORK5CYII=",
    )
    private val url = "/api/tool-media/e0c7fa51151aa052a8da3332dfbca5b8bd02c4176ad6dcc91bc4525c0cd000b6.png"

    private fun captured200() = MockResponse()
        .setResponseCode(200)
        .setHeader("Cache-Control", "private, max-age=31536000, immutable")
        .setHeader("Content-Type", "image/png")
        .setBody(Buffer().write(png))

    private fun wire(): ChatFixtures.Folded {
        val text = checkNotNull(javaClass.classLoader!!.getResource("tool-media-wire/events.json")).readText()
        val events = JsCodec.parse(text) as JsArr
        val tree = events.fold(freshTree()) { acc, e -> reduce(acc, e as JsObj) }
        return ChatFixtures.Folded(checkNotNull(LegacyProjectionAdapter.adaptOnce(tree)), tree)
    }

    private fun show(respond: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        server.start()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val origin = server.url("/")
        val source = HttpToolMedia(http) { FilesAuthority.Paired(origin) { it.header("Authorization", "Bearer tthr_device") } }
        val loader = ToolMediaRepository(source, rule.activity.cacheDir, origin.toString().trimEnd('/'))
        val folded = wire()
        rule.setContent {
            ChatHost(TetherSkin.StudioDark) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                    ChatTranscript(
                        projection = folded.projection,
                        tree = folded.tree,
                        showThinking = false,
                        onFetchTurns = { _, _ -> },
                        zone = ChatFixtures.zone,
                        showTimeline = false,
                    )
                }
            }
        }
    }

    private val tile = hasContentDescription("View image full size")

    @Test fun theScreenshotShowsInTheConversationAtItsOwnSize() {
        show { req -> if (req.path == url) captured200() else MockResponse().setResponseCode(404) }
        // The loading placeholder is a 44dp square; the loaded picture is 96 × 60 (CSS px = dp).
        rule.waitUntil(20_000) {
            rule.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes().any { it.size.width > with(rule.density) { 44.dp.roundToPx() } }
        }
        val shown = rule.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes().first()
        with(rule.density) {
            assertEquals(96.dp.roundToPx().toFloat(), shown.size.width.toFloat(), 1f)
            assertEquals(60.dp.roundToPx().toFloat(), shown.size.height.toFloat(), 1f)
        }
        assertTrue(rule.onAllNodes(hasText(MediaCopy.IMAGE_UNAVAILABLE), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val req = server.takeRequest()
        assertEquals(url, req.path)
        assertEquals("Bearer tthr_device", req.getHeader("Authorization"))
    }

    @Test fun behindASignInGatewayTheTileSaysSoAndNothingIsFollowed() {
        show {
            MockResponse().setResponseCode(302).setHeader("Location", "https://login.example.test/").setHeader("Content-Type", "text/html").setBody("<html>login</html>")
        }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(MediaCopy.BLOCKED), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, rule.onAllNodes(hasText(MediaCopy.BLOCKED_DETAIL), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodes(hasText(MediaCopy.IMAGE_UNAVAILABLE), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue("nothing from the gateway's answer is shown", rule.onAllNodes(hasText("login", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("one request, the redirect never followed", 1, server.requestCount)
    }

    @Test fun anyOtherFailureKeepsTheGenericLine() {
        show { MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json; charset=utf-8").setBody("""{"error":"Not found."}""") }
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(MediaCopy.IMAGE_UNAVAILABLE), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasText(MediaCopy.BLOCKED), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
