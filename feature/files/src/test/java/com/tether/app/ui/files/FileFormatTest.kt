package com.tether.app.ui.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's pure helpers (workspace-file-browser.tsx 71-112), with outputs from Node/Chrome. */
class FileFormatTest {
    @Test fun previewKindFollowsTheWebsTables() {
        assertEquals(PreviewKind.Image, FileKinds.previewKind("Shot.PNG"))
        assertEquals(PreviewKind.Image, FileKinds.previewKind("logo.svg"))
        assertEquals(PreviewKind.Video, FileKinds.previewKind("clip.mov"))
        assertEquals(PreviewKind.Text, FileKinds.previewKind("index.tsx"))
        assertEquals(PreviewKind.Text, FileKinds.previewKind("page.html"))
        assertEquals(PreviewKind.Text, FileKinds.previewKind("Dockerfile"))
        assertEquals(PreviewKind.Text, FileKinds.previewKind("LICENSE"))
        assertEquals(PreviewKind.Unsupported, FileKinds.previewKind("archive.tar.gz"))
        assertEquals(PreviewKind.Unsupported, FileKinds.previewKind("NOTES"))
        assertEquals(PreviewKind.Unsupported, FileKinds.previewKind(".bashrc"))
        assertTrue(FileKinds.nativeImage("a.webp"))
        assertFalse("no SVG renderer in the app", FileKinds.nativeImage("a.svg"))
    }

    @Test fun sizeMatchesTheWebsFormatSize() {
        // node: formatSize(n) for each n
        val cases = mapOf(
            -1L to "—", 0L to "0 B", 65L to "65 B", 1023L to "1023 B", 1024L to "1.0 KB", 1536L to "1.5 KB",
            10_239L to "10.0 KB", 10_240L to "10 KB", 1_048_575L to "1024 KB", 1_048_576L to "1.0 MB",
            5_347_738L to "5.1 MB", 1_073_741_824L to "1.0 GB", 1_099_511_627_776L * 2048 to "2048 TB",
        )
        for ((bytes, expected) in cases) assertEquals("formatSize($bytes)", expected, FileFormat.size(bytes))
    }

    @Test fun modifiedMatchesIntlMediumShort() {
        assertEquals("Jan 1, 2026, 12:00 AM", FileFormat.modified(FilesFixtures.EPOCH_MS, FilesFixtures.env))
        assertEquals("Sep 27, 2026, 3:04 PM", FileFormat.modified(1_790_521_440_000.7, FilesFixtures.env))
        assertEquals("—", FileFormat.modified(Double.NaN, FilesFixtures.env))
    }

    @Test fun aServerNameNeverEscapesItsLocalDirectory() {
        val hostile = mapOf(
            "../../etc/passwd" to "passwd",
            "..\\..\\evil.dll" to "evil.dll",
            "/abs/path/x.txt" to "x.txt",
            ".." to "file",
            "." to "file",
            "" to "file",
            "   " to "file",
            ".hidden" to "hidden",
            "a/../" to "file",
            "con:*?\"<>|.txt" to "con_______.txt",
            "line\nbreak\u0000.md" to "line_break_.md",
            "invoice\u202Etxt.exe" to "invoice_txt.exe",
            "trailing. . ." to "trailing",
        )
        for ((name, expected) in hostile) assertEquals("safe(${name.replace("\n", "\\n")})", expected, LocalNames.safe(name))
        val long = "a".repeat(400) + ".tar.gz"
        val safe = LocalNames.safe(long)
        assertEquals(LocalNames.MAX_LOCAL_NAME, safe.length)
        assertTrue(safe.endsWith(".gz"))
    }

    @Test fun sharedCopiesStayInsideTheirOwnRandomDirectory() {
        val cacheDir = kotlin.io.path.createTempDirectory("files-cache").toFile()
        val cache = FileCache(cacheDir)
        for (name in listOf("../../../files/credentials/x", "..", "a/b/c.png", "/etc/shadow", "ok.txt")) {
            val file = cache.newShareFile(name)
            val shareRoot = File(cache.root, FileCache.SHARE_DIR).canonicalFile
            assertEquals("$name stays in one share dir", shareRoot, file.canonicalFile.parentFile.parentFile)
            assertFalse(file.name.contains('/'))
        }
        val a = cache.newScratch()
        val b = cache.newScratch()
        assertTrue(a.name.startsWith("preview-") && a.name != b.name)
        assertEquals(cache.root.canonicalFile, a.canonicalFile.parentFile)
        assertEquals("under the app cache dir", File(cacheDir, "workspace-files").canonicalFile, cache.root.canonicalFile)
        cacheDir.deleteRecursively()
    }

    @Test fun sweepEmptiesTheCacheOrKeepsOnlyFreshShares() {
        val cacheDir = kotlin.io.path.createTempDirectory("files-cache").toFile()
        val cache = FileCache(cacheDir)
        cache.newScratch().writeText("x")
        val old = cache.newShareFile("old.txt").apply { writeText("x") }
        old.parentFile.setLastModified(1_000)
        val fresh = cache.newShareFile("fresh.txt").apply { writeText("x") }
        cache.sweep(now = fresh.parentFile.lastModified() + 1_000, keepSharesYoungerThanMs = FileCache.SHARE_GRACE_MS)
        assertTrue(fresh.exists())
        assertFalse(old.exists())
        assertTrue(cache.root.listFiles()!!.none { it.name.startsWith("preview-") })
        cache.sweep()
        assertTrue(cache.root.listFiles().isNullOrEmpty())
        cacheDir.deleteRecursively()
    }

    @Test fun theBackupRulesNeverIncludeTheCache() {
        // Android never backs up getCacheDir(); only an <include domain="root"> could pull it in.
        for (name in listOf("data_extraction_rules.xml", "backup_rules.xml")) {
            val xml = File("../../app/src/main/res/xml/$name").readText()
            assertFalse("$name includes nothing (exclusions only)", xml.contains("<include"))
        }
        val paths = File("src/main/res/xml/workspace_file_paths.xml").readText()
        assertEquals("the provider serves one cache subfolder", 1, Regex("<(cache|files|external|root)[-a-z]*-path").findAll(paths).count())
        assertTrue(paths.contains("<cache-path name=\"shared\" path=\"workspace-files/share/\" />"))
    }

    @Test fun decodesAreSampledDownPastTheSideCap() {
        assertEquals(1, BoundedImages.sampleSize(4096, 100, 4096))
        assertEquals(2, BoundedImages.sampleSize(4097, 100, 4096))
        // A 30000 x 30000 claim (decompression bomb) decodes at 1/8: 3750 px a side, not 3.6 GB.
        assertEquals(8, BoundedImages.sampleSize(30_000, 30_000, 4096))
    }

    @Test fun viewersNeverUseAWebView() {
        // Text is Compose Text and images are bitmaps: nothing in this feature can run content.
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            assertFalse("${file.name} uses a WebView", Regex("\\bWebView\\b|android\\.webkit|evaluateJavascript|loadData").containsMatchIn(text))
        }
    }
}
