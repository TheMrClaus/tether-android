package com.tether.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-9jnm F1: which mentions are file links. Lexical only (no file system, no network): a token is a candidate by its shape.
 * Positives first, so a detector that links nothing cannot pass the negatives.
 */
class FileLinkDetectionTest {
    private val cwd = "/w/p"

    private fun paths(text: String, source: FileLinkSource = FileLinkSource.Text, cwd: String = this.cwd) =
        FileLinks.detect(text, source, cwd).map { it.path }

    private fun linked(text: String, source: FileLinkSource = FileLinkSource.Text): List<String> =
        FileLinks.detect(text, source, cwd).map { text.substring(it.start, it.end) }

    // ---- positives ------------------------------------------------------------------------------

    @Test fun anAbsolutePathWithAKnownExtensionLinks() {
        assertEquals(listOf("/home/u/a.png"), paths("see /home/u/a.png now"))
    }

    @Test fun inlineCodeLinksAsAWhole() {
        assertEquals(listOf("/tmp/x.log"), paths("/tmp/x.log", FileLinkSource.Code))
        assertEquals(listOf("/w/p/dir/my file.md"), paths("dir/my file.md", FileLinkSource.Code))
    }

    @Test fun aRelativePathInCodeResolvesAgainstTheSessionFolder() {
        assertEquals(listOf("/w/p/src/app/Main.kt"), paths("src/app/Main.kt", FileLinkSource.Code))
        assertEquals(listOf("/w/p/Main.kt"), paths("./Main.kt", FileLinkSource.Code))
        assertEquals(listOf("/w/x.md"), paths("../x.md", FileLinkSource.Code))
    }

    @Test fun aMarkdownHrefIsAnyAbsolutePath() {
        assertEquals("/home/u/n.md", FileLinks.hrefPath("/home/u/n.md"))
        assertEquals("/home/u/n x.md", FileLinks.hrefPath("file:///home/u/n%20x.md"))
        assertEquals("/home/u/src", FileLinks.hrefPath("/home/u/src"))
        assertEquals("/a/b.kt", FileLinks.hrefPath("/a/b.kt#L10-L20"))
    }

    @Test fun aLineSuffixIsDrawnInTheLinkButNotInTheTarget() {
        for ((text, drawn) in listOf(
            "/a/b.kt:12" to "/a/b.kt:12",
            "/a/b.kt:12:3" to "/a/b.kt:12:3",
            "/a/b.kt:12-14" to "/a/b.kt:12-14",
            "/a/b.kt#L10-L20" to "/a/b.kt#L10-L20",
            "/a/b.kt#L10C2" to "/a/b.kt#L10C2",
            "/a/b.kt(12)" to "/a/b.kt(12)",
            "/a/b.kt(12,3)" to "/a/b.kt(12,3)",
        )) {
            assertEquals(text, listOf("/a/b.kt"), paths(text))
            assertEquals(text, listOf(drawn), linked(text))
        }
    }

    @Test fun closingPunctuationIsNotPartOfTheLink() {
        assertEquals(listOf("/a/b.png"), paths("(/a/b.png)."))
        assertEquals(listOf("/a/b.png"), linked("(/a/b.png)."))
        assertEquals(listOf("/a/b.png"), linked("\"/a/b.png\","))
        assertEquals(listOf("/a/b.kt:12"), linked("(see /a/b.kt:12):"))
        assertEquals(listOf("/a/b.png", "/a/c.png"), paths("/a/b.png, /a/c.png!"))
    }

    @Test fun aMatchedParenthesisStaysInTheName() {
        assertEquals(listOf("/a/foo(1).png"), paths("/a/foo(1).png"))
        assertEquals(listOf("/a/foo(1).png"), linked("/a/foo(1).png."))
    }

    @Test fun aNameWithoutAnExtensionNeedsToBeAKnownName() {
        assertEquals(listOf("/a/Makefile"), paths("/a/Makefile"))
        assertEquals(listOf("/a/Dockerfile"), paths("/a/Dockerfile"))
    }

    @Test fun everyTokenOfALineLinks() {
        assertEquals(listOf("/a/b.kt", "/c/d.png"), paths("/a/b.kt\t/c/d.png then\n/e"))
    }

    // ---- negatives ------------------------------------------------------------------------------

    @Test fun aUrlIsNeverAFilePath() {
        assertEquals(emptyList<String>(), paths("https://example.test/a.png and http://h/x/y.md and ftp://h/a/b.txt"))
        assertEquals(emptyList<String>(), paths("https://example.test/a/b.png", FileLinkSource.Code))
    }

    @Test fun aProtocolRelativeAddressIsNotAFilePath() {
        assertEquals(emptyList<String>(), paths("//cdn/a.js"))
        assertEquals(emptyList<String>(), paths("//cdn/a.js", FileLinkSource.Code))
    }

    @Test fun ordinaryProseWithASlashIsNotAFilePath() {
        assertEquals(emptyList<String>(), paths("and/or 10/09 yes/no a/b TCP/IP"))
        assertEquals(emptyList<String>(), paths("and/or", FileLinkSource.Code))
    }

    @Test fun anApiRouteIsNotAFilePath() {
        assertEquals(emptyList<String>(), paths("/api/files"))
        assertEquals(emptyList<String>(), paths("GET /api/files?path=/a.png then"))
        assertEquals(emptyList<String>(), paths("/api/files?path=/a.png", FileLinkSource.Code))
    }

    @Test fun aRelativePathInPlainTextNeverLinks() {
        assertEquals(emptyList<String>(), paths("edit src/a.kt and ./b.kt and Main.kt"))
    }

    @Test fun aRelativePathNeedsAKnownSessionFolder() {
        assertEquals(emptyList<String>(), paths("src/a.kt", FileLinkSource.Code, cwd = ""))
        assertEquals(listOf("/w/p/src/a.kt"), paths("src/a.kt", FileLinkSource.Code))
    }

    @Test fun aRelativePathNeedsAKnownExtension() {
        assertEquals(emptyList<String>(), paths("git status", FileLinkSource.Code))
        assertEquals(emptyList<String>(), paths("src/app", FileLinkSource.Code))
        assertEquals(emptyList<String>(), paths("foo.unknownext", FileLinkSource.Code))
    }

    @Test fun aFolderOrAShortPathIsNotLinkedFromProse() {
        assertEquals(emptyList<String>(), paths("/usr/bin and /usr/lib/ and /a.png and /"))
    }

    @Test fun aHomeShortcutOrAnOptionIsNotAFilePath() {
        assertEquals(emptyList<String>(), paths("~/a.png and -/a/b.png"))
        assertEquals(emptyList<String>(), paths("~/a.png", FileLinkSource.Code))
    }

    @Test fun aClimbOutOfTheRootIsRefused() {
        assertEquals(emptyList<String>(), paths("../../../x.md", FileLinkSource.Code))
        assertNull(FileLinks.hrefPath("/../x.md"))
    }

    @Test fun aHiddenCharacterRefusesTheToken() {
        assertEquals(emptyList<String>(), paths("/a/b\u202E.png and /a/c\u200B.png and /a/d\u0007.png"))
        assertNull(FileLinks.hrefPath("/a/b\u202Egnp.md"))
    }

    @Test fun aWildcardOrAShellMetacharacterRefusesTheToken() {
        assertEquals(emptyList<String>(), paths("/a/*.png /a/b|c.png /a/<b>.png"))
    }

    @Test fun aBadPercentEscapeRefusesTheHref() {
        assertNull(FileLinks.hrefPath("file:///a/%zz.md"))
        assertNull(FileLinks.hrefPath("file:///a/%E0%A4%A.md"))
        assertNull(FileLinks.hrefPath("file://host/a.md"))
        assertNull(FileLinks.hrefPath("a/b.md"))
        assertNull(FileLinks.hrefPath("//cdn/a.md"))
        assertNull(FileLinks.hrefPath("https://x/a.md"))
    }

    @Test fun aHugeTokenIsRefused() {
        assertEquals(emptyList<String>(), paths("/" + "a".repeat(1100) + "/b.png"))
    }

    @Test fun atMostOneHundredLinksAreDrawnInARun() {
        val text = (1..150).joinToString(" ") { "/a/f$it.png" }
        assertEquals(100, paths(text).size)
        assertTrue(FileLinks.detect(text, FileLinkSource.Text, cwd, limit = 7).size == 7)
    }

    @Test fun theSessionFolderIsFolded() {
        assertEquals(listOf("/w/p/c.kt"), FileLinks.detect("a/../c.kt", FileLinkSource.Code, "/w/p/").map { it.path })
        assertEquals("/a/c", FileLinks.normalize("/a/./b/../c//"))
    }

    // ---- the markdown parser hands hrefs over -----------------------------------------------------

    @Test fun aPathHrefParsesToAFileLinkAndAnythingElseStaysInert() {
        assertEquals(
            listOf(MdInline.FileLink("/home/u/n x.md", listOf(MdInline.Text("n")))),
            parseInline("[n](file:///home/u/n%20x.md)"),
        )
        assertEquals(listOf(MdInline.FileLink("/home/u/n.md", listOf(MdInline.Text("n")))), parseInline("[n](/home/u/n.md)"))
        assertEquals(listOf(MdInline.Span(listOf(MdInline.Text("n")))), parseInline("[n](docs/n.md)"))
        assertEquals(listOf(MdInline.Link("https://x.test/a.md", listOf(MdInline.Text("n")))), parseInline("[n](https://x.test/a.md)"))
    }

    @Test fun anImageKeepsItsPictureAndItsFallbackSpan() {
        assertTrue(parseInline("![shot](/tmp/a.png)").single() is MdInline.Image)
        assertEquals(listOf(MdInline.Span(listOf(MdInline.Text("shot")))), parseInline("![shot](ftp://h/x.png)"))
    }
}
