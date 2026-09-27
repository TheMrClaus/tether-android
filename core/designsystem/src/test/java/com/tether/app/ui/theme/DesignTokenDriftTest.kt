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
        // machine --graphite is #111517 (raw and resolved); change the resolved value only.
        val needle = "\"resolved\": \"#111517\""
        assertTrue("fixture value present", original.contains(needle))
        val copy = tmp.newFile("design-tokens.json").apply { writeText(original.replaceFirst(needle, "\"resolved\": \"#111518\"")) }
        val drift = verify(copy, TokenCorpus.generatedFile, tmp.root.resolve("out/GeneratedTokens.kt"))
        assertNotNull("changed JSON must fail verification", drift)
        assertTrue(drift!!, drift.contains("Design tokens drifted"))
        assertTrue(drift, drift.contains("0xFF111518"))
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
