package com.tether.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.tether.app.client.FilesResult
import com.tether.app.client.HttpPublicImages
import com.tether.app.client.PublicImageSource
import com.tether.app.client.ToolMediaResult
import com.tether.app.client.ToolMediaSource
import com.tether.app.client.WorkspaceFiles
import com.tether.app.ui.chat.MdInline.Em
import com.tether.app.ui.chat.MdInline.Image
import com.tether.app.ui.chat.MdInline.Link
import com.tether.app.ui.chat.MdInline.Span
import com.tether.app.ui.chat.MdInline.Strong
import com.tether.app.ui.chat.MdInline.Text
import com.tether.app.ui.theme.TetherSkin
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ta-coik.58 (#242): `![alt](src)` in chat prose. The web's `resolveImageSrc` / `MarkdownImage`
 * (components/markdown.tsx 29537e0 :34-106, :236), pinned: what a target resolves to, how the parse
 * places the image, how the paragraph flows around it, what a tap and a failed load do, and where
 * each source's bytes come from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ProseImagesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val server = MockWebServer()

    @After fun tearDown() {
        runCatching { server.shutdown() }
        Unit
    }

    // ── resolveImageSrc / imageBasename (markdown.tsx:34-77) ─────────────────────────────────

    @Test fun anAbsolutePathAndAFileUrlGoThroughApiFilesAndAnHttpUrlIsUsedAsIs() {
        assertEquals("/api/files?path=%2Ftmp%2Fa%20b.png", resolveImageSrc("/tmp/a b.png"))
        assertEquals("/api/files?path=%2Ftmp%2Fa%20b.png", resolveImageSrc("file:///tmp/a%20b.png"))
        assertEquals("/api/files?path=%2Ftmp%2Fa.png", resolveImageSrc("FILE:///tmp/a.png"))
        assertEquals("https://example.test/x.png?y=1", resolveImageSrc("https://example.test/x.png?y=1"))
        assertEquals("HTTP://example.test/x.png", resolveImageSrc("HTTP://example.test/x.png"))
        val media = "/api/tool-media/" + "a".repeat(64) + ".png"
        assertEquals(media, resolveImageSrc(media))
        assertEquals("/api/files?path=%2Ftmp%2Fa.png", resolveImageSrc("/api/files?path=%2Ftmp%2Fa.png"))
    }

    @Test fun everythingElseIsNotAnImageSource() {
        for (bad in listOf(
            "data:image/png;base64,AAAA", "javascript:alert(1)", "relative/pic.png", "./pic.png", "//host/x.png",
            "file://host/x.png", "file:relative.png", "file:///tmp/%E0%A4%A.png", "ftp://h/x.png", "", "/api/tool-media/short.png",
        )) {
            // "/api/tool-media/short.png" is an absolute path: it is served as a FILE, never as tool media.
            val got = resolveImageSrc(bad)
            if (bad == "/api/tool-media/short.png") assertEquals("/api/files?path=%2Fapi%2Ftool-media%2Fshort.png", got) else assertNull(bad, got)
        }
        // Only ASCII folds: a Turkish dotless i is not "https".
        assertNull(resolveImageSrc("httıps://h/x.png"))
    }

    @Test fun anEmptyAltIsLabelledByTheFileName() {
        assertEquals("shot.png", imageBasename("/tmp/dir/shot.png"))
        assertEquals("shot.png", imageBasename("/api/files?path=%2Ftmp%2Fshot.png"))
        assertEquals("x.png", imageBasename("https://h/a/x.png?v=1#f"))
        assertEquals("image", imageBasename("/"))
    }

    // ── the parse (markdown.tsx:236-247) ─────────────────────────────────────────────────────

    @Test fun anImageIsOneNodeBeforeTheLinkRule() {
        assertEquals(
            listOf(Text("see "), Image("/api/files?path=%2Ftmp%2Fa.png", "the shot"), Text(" now")),
            parseInline("see ![the shot](/tmp/a.png) now"),
        )
        // The alt is trimmed; an empty one is the file name.
        assertEquals(listOf(Image("/api/files?path=%2Ftmp%2Fa.png", "a.png")), parseInline("![  ](/tmp/a.png)"))
        assertEquals(listOf(Image("https://h/p.png", "p")), parseInline("![ p ](https://h/p.png)"))
    }

    @Test fun anUnsafeTargetDegradesToItsAltTextOrTheTarget() {
        assertEquals(listOf(Span(listOf(Text("alt")))), parseInline("![alt](javascript:void)"))
        assertEquals(listOf(Span(listOf(Text("alt")))), parseInline("![alt](data:image/png;base64,AAAA)"))
        assertEquals(listOf(Span(listOf(Text("data:x")))), parseInline("![](data:x)"))
        assertEquals(listOf(Span(listOf(Strong(listOf(Text("b")))))), parseInline("![**b**](rel.png)"))
    }

    @Test fun theLinkRuleStillWinsAnEarlierBracketLikeTheWeb() {
        // markdown.tsx: the earliest match wins, and `[![badge](u)](v)` has its link at index 0 (label
        // "![badge", href u) before the image at index 1: the web draws that line the same way.
        assertEquals(
            listOf(Link("https://h/b.png", listOf(Text("![badge"))), Text("](https://x.test)")),
            parseInline("[![badge](https://h/b.png)](https://x.test)"),
        )
        // An image inside emphasis is an image inside the emphasis.
        assertEquals(listOf(Strong(listOf(Image("/api/files?path=%2Fa.png", "a.png")))), parseInline("**![](/a.png)**"))
    }

    @Test fun anImageIsInTheFindCountAsNothing() {
        assertEquals(0, countMarkdownMatches("![needle](/tmp/needle.png)", "needle"))
        assertEquals(1, countMarkdownMatches("![needle](rel/x.png)", "needle")) // an unsafe target is its alt text
    }

    // ── the flow around it (markdown.tsx:236-247; globals.css .md-img-link inline-block) ─────

    private fun pieces(text: String) = proseTextPieces((parseMarkdown(text).single() as MdBlock.Paragraph).lines)

    @Test fun picturesOnOneLineSitSideBySideAndOnSeparateLinesTheyStack() {
        val a = Image("/api/files?path=%2Fa.png", "a.png")
        val b = Image("/api/files?path=%2Fb.png", "b.png")
        assertEquals(listOf<ProsePiece>(ProsePiece.Images(listOf(a, b))), pieces("![](/a.png) ![](/b.png)"))
        assertEquals(listOf<ProsePiece>(ProsePiece.Images(listOf(a)), ProsePiece.Images(listOf(b))), pieces("![](/a.png)\n![](/b.png)"))
    }

    @Test fun textAroundAPictureStaysTextAndNoBlankLineAppears() {
        val a = Image("/api/files?path=%2Fa.png", "a.png")
        assertEquals(
            listOf(
                ProsePiece.Text(listOf(listOf(Text("before")))),
                ProsePiece.Images(listOf(a)),
                ProsePiece.Text(listOf(listOf(Text("after")))),
            ),
            pieces("before\n![](/a.png)\nafter"),
        )
        assertEquals(1, pieces("no pictures here\nnext line").size)
    }

    @Test fun aStyledOrLinkedRunKeepsItsStyleAroundAPicture() {
        val a = Image("/api/files?path=%2Fa.png", "a.png")
        val got = pieces("**bold ![](/a.png) more**")
        assertEquals(
            listOf(
                ProsePiece.Text(listOf(listOf(Strong(listOf(Text("bold")))))),
                ProsePiece.Images(listOf(a)),
                ProsePiece.Text(listOf(listOf(Strong(listOf(Text("more")))))),
            ),
            got,
        )
        assertTrue(Em(listOf(Text("x"))) is MdInline)
    }

    @Test fun theBlanksAtTheCutAreTrimmedSoNoStraySpaceStartsOrEndsALine() {
        val b = Image("https://ci.example.test/badge.png", "ci")
        assertEquals(
            listOf(
                ProsePiece.Text(listOf(listOf(Text("the badge")))),
                ProsePiece.Images(listOf(b)),
                ProsePiece.Text(listOf(listOf(Text("sits inline with the text.")))),
            ),
            pieces("the badge ![ci](https://ci.example.test/badge.png) sits inline with the text."),
        )
        // Inner spacing and a link's own edges survive; only the cut's side is trimmed.
        assertEquals(
            listOf(
                ProsePiece.Text(listOf(listOf(Text("a  b")))),
                ProsePiece.Images(listOf(b)),
                ProsePiece.Text(listOf(listOf(Text("c  d")))),
            ),
            pieces("a  b   ![ci](https://ci.example.test/badge.png)   c  d"),
        )
    }

    // ── the drawn image ──────────────────────────────────────────────────────────────────────

    private fun show(text: String, loader: ToolMediaLoader) {
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 480.dp) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                    MarkdownText(text, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun aPictureShowsAtItsOwnSizeWithItsAltAndATapOpensTheViewer() {
        val loader = ToolFixtures.FakeLoader(MediaImage.Ok(ToolFixtures.checker(96, 60)))
        show("Here it is: ![the shot](/tmp/a.png)", loader)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("md-image")).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onNodeWithTag("md-image").fetchSemanticsNode()
        with(rule.density) {
            assertEquals(96.dp.roundToPx().toFloat(), node.size.width.toFloat(), 1f)
            assertEquals(60.dp.roundToPx().toFloat(), node.size.height.toFloat(), 1f)
        }
        rule.onNodeWithContentDescription("the shot").assertExists()
        rule.onNodeWithText("Here it is:", substring = true).assertExists()
        assertEquals(listOf("/api/files?path=%2Ftmp%2Fa.png"), loader.loads.distinct())
        rule.onAllNodesWithTag("media-lightbox").assertCountEquals(0)
        rule.onNodeWithTag("md-image").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("media-lightbox").assertExists()
    }

    // r2 sizing: laid out from the SOURCE's natural pixels (`.md-img`: natural size, max-width 100%
    // of the column, max-height 320px, aspect kept), whatever the (sampled) decode's own size is.
    private fun drawn(natural: Pair<Int, Int>, columnDp: Int): Pair<Float, Float> {
        // The decode is a small stand-in: the layout must come from the natural size, not from it.
        val loader = ToolFixtures.FakeLoader(MediaImage.Ok(ToolFixtures.checker(40, 25), natural.first, natural.second))
        rule.setContent {
            ChatHost(TetherSkin.StudioDark, wellHeight = 900.dp) {
                CompositionLocalProvider(LocalToolMediaLoader provides loader) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(columnDp.dp)) {
                        MarkdownText("![shot](/tmp/a.png)", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("md-image")).fetchSemanticsNodes().isNotEmpty() }
        val size = rule.onNodeWithTag("md-image").fetchSemanticsNode().size
        return with(rule.density) { size.width.toDp().value to size.height.toDp().value }
    }

    @Test fun aWideScreenshotFillsTheColumnAndKeepsItsAspect() {
        val (w, h) = drawn(1280 to 800, columnDp = 360)
        assertEquals(360f, w, 1f)
        assertEquals(225f, h, 1f)
    }

    @Test fun aTallPhoneShotIsCappedAt320DpHighAndKeepsItsAspect() {
        val (w, h) = drawn(1080 to 2400, columnDp = 360)
        assertEquals(320f, h, 1f)
        assertEquals(144f, w, 1f)
    }

    @Test fun aSmallImageKeepsItsNaturalSize() {
        val (w, h) = drawn(120 to 80, columnDp = 360)
        assertEquals(120f, w, 1f)
        assertEquals(80f, h, 1f)
    }

    @Test fun aFailedLoadDegradesToTheAltAndImageUnavailable() {
        show("![the shot](/tmp/a.png)", ToolFixtures.FakeLoader(MediaImage.Failed))
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("md-image-missing")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("the shot (image unavailable)").assertExists()
        rule.onAllNodesWithTag("md-image").assertCountEquals(0)
    }

    @Test fun anUnsafeTargetLoadsNothingAndShowsItsAlt() {
        val loader = ToolFixtures.FakeLoader()
        show("![just text](javascript:alert(1)) and ![](data:x)", loader)
        rule.onNodeWithText("just text", substring = true).assertExists()
        rule.onAllNodesWithTag("md-image").assertCountEquals(0)
        assertTrue(loader.loads.isEmpty())
    }

    @Test fun anEmphasisedPictureAndATableCellBothDrawThePicture() {
        val loader = ToolFixtures.FakeLoader(MediaImage.Ok(ToolFixtures.checker(40, 20)))
        show("**![badge](https://h.test/b.png)**\n\n| a | b |\n|---|---|\n| ![cell](/tmp/c.png) | text |", loader)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("md-image")).fetchSemanticsNodes().size == 2 }
        assertEquals(setOf("https://h.test/b.png", "/api/files?path=%2Ftmp%2Fc.png"), loader.loads.toSet())
    }

    // ── where the bytes come from (ToolMediaRepository) ──────────────────────────────────────

    private val png: ByteArray = Base64.getDecoder().decode(ComposerAttachmentFixtures.PNG_BASE64)

    private class FakeFiles(private val bytes: ByteArray?, private val tooLarge: Boolean = false) : WorkspaceFiles by WorkspaceFiles.Unavailable {
        val paths = mutableListOf<String>()
        var cap = -1L
        override suspend fun download(path: String, maxBytes: Long, sink: OutputStream): FilesResult<Long> {
            paths += path
            cap = maxBytes
            if (tooLarge) return FilesResult.Failed("big", 200, tooLarge = true)
            val b = bytes ?: return FilesResult.Failed("nope", 404)
            sink.write(b)
            return FilesResult.Ok(b.size.toLong())
        }
    }

    private fun repo(files: WorkspaceFiles = WorkspaceFiles.Unavailable, remote: PublicImageSource = PublicImageSource.Unavailable) =
        ToolMediaRepository(ToolMediaSource.Unavailable, rule.activity.cacheDir, "https://tether.test", files = files, remote = remote)

    private fun item(src: String) = ToolMediaItem(ToolMediaItem.KIND_IMAGE, "image/*", src)

    @Test fun aServedFileIsReadOverApiFilesByItsDecodedPath() = runBlocking {
        val files = FakeFiles(png)
        val got = repo(files).image(item("/api/files?path=%2Ftmp%2Fa%20b%C3%A9.png"))
        assertTrue("$got", got is MediaImage.Ok)
        assertEquals(listOf("/tmp/a bé.png"), files.paths)
        assertEquals(MediaLimits.MAX_IMAGE_BYTES, files.cap)
    }

    @Test fun aProsePictureKeepsItsNaturalSizeAndDecodesUnderItsOwnLargerBound() = runBlocking {
        val big = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(2560, 1440, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val r = repo(FakeFiles(big))
        val tile = r.image(item("/api/files?path=%2Ftmp%2Fa.png")) as MediaImage.Ok
        val prose = r.prose(item("/api/files?path=%2Ftmp%2Fa.png")) as MediaImage.Ok
        // The tile is sampled to <= 512 (2560 -> /8 = 320), the prose picture to <= 1280 (/2 = 1280x720);
        // both carry the source's real size, and each tier is cached under its own key.
        assertEquals(320, tile.bitmap.width)
        assertEquals(1280, prose.bitmap.width)
        assertEquals(2560 to 1440, prose.naturalWidth to prose.naturalHeight)
        assertEquals(2560 to 1440, tile.naturalWidth to tile.naturalHeight)
        assertTrue(prose.bitmap.width * prose.bitmap.height * 4L <= MediaLimits.PROSE_DECODED_BYTES)
    }

    @Test fun aServedFileThatIsNotAPictureOrIsMissingOrTooLargeFails() = runBlocking {
        assertEquals(MediaImage.Failed, repo(FakeFiles("<html>not an image</html>".toByteArray())).image(item("/api/files?path=%2Ftmp%2Fa.png")))
        assertEquals(MediaImage.Failed, repo(FakeFiles(null)).image(item("/api/files?path=%2Ftmp%2Fa.png")))
        assertEquals(MediaImage.TooLarge, repo(FakeFiles(null, tooLarge = true)).image(item("/api/files?path=%2Ftmp%2Fa.png")))
        assertEquals(MediaImage.Failed, repo(FakeFiles(png)).image(item("/api/files?path=%E0%A4%A")))
        Unit
    }

    @Test fun anHttpPictureCarriesNeitherCredentialNorCookieNorReferrer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
        server.start()
        val url = server.url("/pics/x.png").toString()
        val got = repo(remote = HttpPublicImages()).image(item(url))
        assertTrue("$got", got is MediaImage.Ok)
        val req = server.takeRequest()
        assertEquals("/pics/x.png", req.path)
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("Cookie"))
        assertNull(req.getHeader("Referer"))
    }

    @Test fun anHttpPictureThatIsTooBigOrNotAPictureFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody("plain text"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        assertEquals(MediaImage.Failed, repo(remote = HttpPublicImages()).image(item(server.url("/a.png").toString())))
        assertEquals(MediaImage.Failed, repo(remote = HttpPublicImages()).image(item(server.url("/b.png").toString())))
        // Only http(s) is ever requested; a body past the cap is refused as it streams.
        assertEquals(ToolMediaResult.Refused, HttpPublicImages().fetch("ftp://h/x.png", 10, java.io.ByteArrayOutputStream()))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody("x".repeat(50)))
        assertEquals(ToolMediaResult.TooLarge, HttpPublicImages().fetch(server.url("/c.png").toString(), 10, java.io.ByteArrayOutputStream()))
    }

    @Test fun withNoFilesOrRemoteSourceThePictureIsUnavailable() = runBlocking {
        assertEquals(MediaImage.Failed, repo().image(item("/api/files?path=%2Ftmp%2Fa.png")))
        assertEquals(MediaImage.Failed, repo().image(item("https://h.test/a.png")))
        Unit
    }
}
