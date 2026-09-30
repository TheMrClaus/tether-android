package com.tether.app.ui.theme

import com.tether.tools.designtokens.generate
import com.tether.tools.designtokens.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D9 drift guard, the same check `./gradlew verifyDesignTokens` runs: the checked-in
 * GeneratedTokens.kt must equal a fresh generation of the vendored JSON, and any change to the
 * JSON (exercised on a temp copy) must make verification fail.
 */
class DesignTokenDriftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun checkedInFileMatchesAFreshGeneration() {
        assertEquals(generate(TokenCorpus.jsonFile.readText()), TokenCorpus.generatedFile.readText())
    }

    @Test
    fun generationIsDeterministic() {
        val json = TokenCorpus.jsonFile.readText()
        assertEquals(generate(json), generate(json))
    }

    @Test
    fun verifyPassesOnAnUnchangedCopy() {
        val copy = tmp.newFile("design-tokens.json").apply { writeText(TokenCorpus.jsonFile.readText()) }
        assertNull(verify(copy, TokenCorpus.generatedFile, tmp.root.resolve("out/GeneratedTokens.kt")))
    }

    @Test
    fun verifyFailsWhenATokenValueChanges() {
        val original = TokenCorpus.jsonFile.readText()
        // studio-dark --graphite is #172032 (raw and resolved); change the resolved value only.
        val needle = "\"resolved\": \"#172032\""
        assertTrue("fixture value present", original.contains(needle))
        val copy = tmp.newFile("design-tokens.json").apply { writeText(original.replaceFirst(needle, "\"resolved\": \"#172033\"")) }
        val drift = verify(copy, TokenCorpus.generatedFile, tmp.root.resolve("out/GeneratedTokens.kt"))
        assertNotNull("changed JSON must fail verification", drift)
        assertTrue(drift!!, drift.contains("Design tokens drifted"))
        assertTrue(drift, drift.contains("0xFF172033"))
    }

    /** T15.5: an export that still carries the retired theme families is refused, not generated. */
    @Test
    fun anExportWithThemeFamiliesIsRefused() {
        val original = TokenCorpus.jsonFile.readText()
        val withFamilies = original.replaceFirst("{\n", "{\n  \"families\": [\"studio\"],\n")
        val error = runCatching { generate(withFamilies) }.exceptionOrNull()
        assertNotNull("a family-bearing export must not generate", error)
        assertTrue(error.toString(), error!!.message!!.contains("theme families are retired"))
    }

    @Test
    fun verifyFailsWhenTheCorpusShaChanges() {
        val original = TokenCorpus.jsonFile.readText()
        val sha = DesignTokenSource.TETHER_SHA
        val copy = tmp.newFile("design-tokens.json").apply {
            writeText(original.replace("\"tetherSha\": \"$sha\"", "\"tetherSha\": \"0000000\""))
        }
        assertNotNull(verify(copy, TokenCorpus.generatedFile, tmp.root.resolve("out/GeneratedTokens.kt")))
    }
}
