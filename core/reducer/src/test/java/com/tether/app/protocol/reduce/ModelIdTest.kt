package com.tether.app.protocol.reduce

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdTest {

    @Test
    fun identicalIdsDoNotDiverge() {
        assertFalse(modelsDiverge("claude-fable-5", "claude-fable-5"))
    }

    @Test
    fun differentFamiliesDiverge() {
        assertTrue(modelsDiverge("claude-fable-5", "claude-opus-4-8"))
        assertTrue(modelsDiverge("claude-opus-4-8", "claude-opus-4-5"))
        assertTrue(modelsDiverge("claude-fable-5", "Opus 4.5"))
    }

    @Test
    fun displayNameContainedInIdDoesNotDiverge() {
        assertFalse(modelsDiverge("claude-fable-5", "Fable"))
        assertFalse(modelsDiverge("claude-opus-4-5", "Opus 4.5"))
    }

    @Test
    fun datedVariantDoesNotDiverge() {
        assertFalse(modelsDiverge("claude-sonnet-5", "claude-sonnet-5-20260203"))
    }

    @Test
    fun normalizationIgnoresCaseAndPunctuation() {
        assertFalse(modelsDiverge("Claude-Fable-5", "claude_fable_5"))
    }

    @Test
    fun missingSideNeverDiverges() {
        assertFalse(modelsDiverge("", "claude-opus-4-8")) // "" = CLI default pick
        assertFalse(modelsDiverge(null, "claude-opus-4-8"))
        assertFalse(modelsDiverge("claude-fable-5", null))
        assertFalse(modelsDiverge("claude-fable-5", ""))
    }
}
