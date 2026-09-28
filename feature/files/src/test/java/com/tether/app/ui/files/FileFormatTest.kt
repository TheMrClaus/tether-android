package com.tether.app.ui.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        assertEquals(LocalNames.MAX_LOCAL_BYTES, safe.toByteArray().size)
        assertTrue(safe.endsWith(".gz"))
    }

    @Test fun aLongNameIsCappedInBytesWithoutSplittingACharacter() {
        // File systems count bytes: 300 CJK characters are 900 bytes of UTF-8.
        for (name in listOf("報告書".repeat(100) + ".pdf", "😀".repeat(120) + ".png", "a" + "😀".repeat(99))) {
            val safe = LocalNames.safe(name)
            val bytes = safe.toByteArray(Charsets.UTF_8)
            assertTrue("$safe is ${bytes.size} bytes", bytes.size <= LocalNames.MAX_LOCAL_BYTES)
            assertEquals("no lone surrogate", safe, String(bytes, Charsets.UTF_8))
        }
        assertTrue(LocalNames.safe("報告書".repeat(100) + ".pdf").endsWith(".pdf"))
        assertTrue(LocalNames.safe("😀".repeat(120) + ".png").endsWith(".png"))
    }

    @Test fun invisibleAndSeparatorCharactersNeverSurvive() {
        assertEquals("zero-width characters are dropped", "invoice.pdf", LocalNames.safe("in\u200Bvo\u200Cic\u200De\uFEFF.pdf"))
        assertEquals("invoice_fdp.exe", LocalNames.safe("invoice\u061Cfdp.exe"))
        assertEquals("a_b_c.txt", LocalNames.safe("a\u2028b\u2029c.txt"))
    }

    @Test fun theContainmentCheckRefusesANameThatBypassedSanitising() {
        val dir = kotlin.io.path.createTempDirectory("files-place").toFile()
        for (raw in listOf("../escape.txt", "../../files/credentials", "a/b.txt", "/etc/passwd", "", ".")) {
            try {
                FileCache.placeIn(dir, raw)
                fail("placeIn accepted \"$raw\"")
            } catch (_: java.io.IOException) {
            }
        }
        assertEquals(File(dir, "ok.txt"), FileCache.placeIn(dir, "ok.txt"))
        dir.deleteRecursively()
    }

    @Test fun decodeSizingSamplesByBytesAndRefusesAPixelBomb() {
        // A 4096² 16-bit PNG decodes to RGBA_F16: 128 MiB at full size, so it is halved to 32 MiB.
        assertEquals(2, BoundedImages.plan(4096, 4096, bytesPerPixel = 8))
        assertEquals(1, BoundedImages.plan(4096, 4096, bytesPerPixel = 4))
        // 8000 x 1000 fits in bytes but not in sides.
        assertEquals(2, BoundedImages.plan(8000, 1000, bytesPerPixel = 4))
        // 100 MP exactly is sampled (sides 2500, 25 MB); a claim past it is refused outright.
        assertEquals(4, BoundedImages.plan(10_000, 10_000, bytesPerPixel = 4))
        assertEquals(null, BoundedImages.plan(30_000, 30_000, bytesPerPixel = 4))
        assertEquals(null, BoundedImages.plan(100_001, 1_000, bytesPerPixel = 4))
        for ((w, h, bpp) in listOf(Triple(4096, 4096, 8), Triple(9999, 9999, 8), Triple(12_000, 8_000, 4), Triple(65_000, 1_500, 8))) {
            val sample = BoundedImages.plan(w, h, bpp)!!
            val bytes = (w / sample).toLong() * (h / sample) * bpp
            assertTrue("$w x $h @$bpp -> 1/$sample = $bytes B", bytes <= BrowserLimits.MAX_DECODED_BYTES && w / sample <= 4096 && h / sample <= 4096)
        }
    }

    @Test fun uploadNamesKeepTheLastSegmentAndRefuseControlCharacters() {
        assertEquals("a.txt", UploadNames.fromDisplayName("C:\\fakepath\\a.txt"))
        assertEquals("b.txt", UploadNames.fromDisplayName("dir/sub/b.txt"))
        assertEquals("upload", UploadNames.fromDisplayName(null))
        assertEquals("upload", UploadNames.fromDisplayName("  "))
        assertEquals(null, UploadNames.fromDisplayName("evil\u0000.txt"))
        assertEquals(null, UploadNames.fromDisplayName("line\nbreak.txt"))
        assertEquals(null, UploadNames.fromDisplayName("del\u007F.txt"))
        assertEquals("résumé 😀.pdf", UploadNames.fromDisplayName("résumé 😀.pdf"))
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

    @Test fun sweepsRemoveOnlyWhatIsPastItsWindowUntilSignOut() {
        val cacheDir = kotlin.io.path.createTempDirectory("files-cache").toFile()
        val cache = FileCache(cacheDir)
        val now = System.currentTimeMillis()
        fun File.age(ms: Long) = apply { walkTopDown().forEach { it.setLastModified(now - ms) } }
        val youngScratch = cache.newScratch().apply { writeText("x") }.age(1_000)
        val oldScratch = cache.newScratch("save").apply { writeText("x") }.age(FileCache.SHARE_GRACE_MS + 1)
        val oldShare = cache.newShareFile("old.txt").apply { writeText("x") }.also { it.parentFile.age(FileCache.SHARE_GRACE_MS + 1) }
        val freshShare = cache.newShareFile("fresh.txt").apply { writeText("x") }.also { it.parentFile.age(1_000) }

        // Opening/closing the browser: by age only (an in-flight scratch and a copy being read stay).
        cache.sweepExpired(now)
        assertTrue(youngScratch.exists() && freshShare.exists())
        assertFalse(oldScratch.exists() || oldShare.exists())

        // Process start: no scratch can be in flight; a fresh share keeps its window.
        cache.sweepAtStartup(now)
        assertFalse(youngScratch.exists())
        assertTrue(freshShare.exists())

        // Sign-out: everything.
        cache.sweepAll()
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
        assertEquals(1, BoundedImages.plan(4096, 100, 4))
        assertEquals(2, BoundedImages.plan(4097, 100, 4))
        // A long strip is sampled by its long side: 30000 x 1000 at 1/8.
        assertEquals(8, BoundedImages.plan(30_000, 1_000, 4))
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
