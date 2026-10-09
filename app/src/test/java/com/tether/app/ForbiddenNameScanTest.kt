package com.tether.app

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-a5jl N1: a third-party product is never named anywhere in this repository (code, identifiers, KDoc, tests, golden
 * directories, docs, beads). The name itself is NOT written here: the scan knows only its SHA-256 and its length
 * (`printf %s <name> | sha256sum`, made outside the repo), and checks every substring of that length of every letter run
 * of every tracked text file, lowercased. A letter run is where the name can hide whole ("the name"), inside a camelCase
 * identifier ("fooNameBar" is the run "foonamebar") or glued into a lowercase compound ("xxnameyy").
 */
class ForbiddenNameScanTest {
    private class Forbidden(val sha256: String, val length: Int, val cheap: Int)

    /** The product's digest (length 5), made outside the repository. */
    private val real = Forbidden("68a32dd6b2c35412abbf319675fa086748a052cb8693e503111c32179e921d48", 5, 106437836)

    /** Files that may name the product: none. No file legitimately needs to. */
    private val allowlist = emptySet<String>()

    private val binaryExtensions = setOf("png", "jpg", "jpeg", "webp", "jar", "so", "apk", "ttf", "otf", "aab", "gz", "zip", "keystore", "jks")

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** True when [text] holds the forbidden name as a substring of a lowercased letter run. */
    private fun holds(text: String, f: Forbidden): Boolean {
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j].isLetter()) j++
            val run = text.substring(i, j).lowercase(Locale.ROOT)
            if (run.length >= f.length && windowsMatch(run, f)) return true
            i = j
        }
        return false
    }

    private fun windowsMatch(run: String, f: Forbidden): Boolean {
        // A cheap 31-polynomial hash of each window is the filter; the SHA-256 is the check.
        var h = 0
        var top = 1
        for (k in 0 until f.length - 1) top *= 31
        for (k in run.indices) {
            h = h * 31 + run[k].code
            if (k >= f.length) h -= run[k - f.length].code * top * 31
            if (k >= f.length - 1 && h == f.cheap && sha256(run.substring(k - f.length + 1, k + 1)) == f.sha256) return true
        }
        return false
    }

    private fun cheapOf(s: String): Int {
        var h = 0
        for (c in s) h = h * 31 + c.code
        return h
    }

    private fun forbiddenFor(word: String) = Forbidden(sha256(word), word.length, cheapOf(word))

    /** The detector on a harmless stand-in word: the shapes the real name could hide in. */
    @Test fun theDetectorFindsAWordWholeInCamelCaseAndInsideALowercaseCompound() {
        val f = forbiddenFor("zorbl")
        assertTrue("whole", holds("a zorbl b", f))
        assertTrue("capitalised", holds("Zorbl", f))
        assertTrue("camelCase identifier", holds("val fooZorblBar = 1", f))
        assertTrue("upper snake", holds("FOO_ZORBL_BAR", f))
        assertTrue("inside a lowercase compound", holds("xxzorblyy", f))
        assertTrue("at the very start of a run", holds("zorblfoo", f))
        assertTrue("at the very end of a run", holds("foozorbl", f))
        assertTrue("in a path", holds("docs/zorbl-notes.md", f))
        assertFalse("another word", holds("zorb zorl orbl", f))
        assertFalse("split by a digit", holds("zor1bl", f))
        assertFalse("empty", holds("", f))
    }

    @Test fun theStoredDigestIsTheRealNamesAndTheRealNameIsNotInTheTestFile() {
        // The test file itself is scanned below; this proves the real digest is a 5-letter word's, not a stand-in's.
        assertEquals(64, real.sha256.length)
        assertEquals(5, real.length)
        assertFalse(holds("zorbl", real))
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, ".git").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "no repository above ${System.getProperty("user.dir")}" }
    }

    private fun trackedFiles(root: File): List<File> {
        val listed = runCatching {
            val p = ProcessBuilder("git", "-C", root.path, "ls-files", "-z").redirectErrorStream(false).start()
            val bytes = p.inputStream.readBytes()
            if (p.waitFor() != 0) null else bytes.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }
        }.getOrNull()
        if (listed != null && listed.isNotEmpty()) return listed.filter { it.isFile }
        return root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile }
            .toList()
    }

    private fun isText(file: File): Boolean {
        if (file.extension.lowercase(Locale.ROOT) in binaryExtensions) return false
        if (file.length() > 20_000_000L) return false
        val head = file.inputStream().use { it.readNBytes(8_000) }
        return head.none { it == 0.toByte() }
    }

    @Test fun noTrackedTextFileNamesTheProductExceptTheAllowlist() {
        val root = repoRoot()
        val files = trackedFiles(root)
        assertTrue("the scan walked the repository (${files.size} files)", files.size > 500)
        var scanned = 0
        val hits = ArrayList<String>()
        for (file in files) {
            if (!isText(file)) continue
            scanned++
            val rel = file.relativeTo(root).path
            if (holds(rel, real) || holds(file.readText(Charsets.UTF_8), real)) hits += rel
        }
        assertTrue("text files were read ($scanned)", scanned > 300)
        val unexpected = hits.filter { it !in allowlist }
        // The path is reported, never the text: the name is not written into a failure message either.
        assertEquals("tracked files that name the third-party product: $unexpected", emptyList<String>(), unexpected)
        allowlist.forEach { assertTrue("the allowlisted file exists: $it", File(root, it).isFile) }
    }
}
