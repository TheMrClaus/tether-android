package com.tether.app.protocol.reduce

import com.tether.app.protocol.SessionCommandOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.CliCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControlsModelTest {

    private fun model(value: String, name: String, current: Boolean? = null, resolved: String? = null) =
        SessionModelOption(value = value, displayName = name, current = current, resolvedModel = resolved)

    // --- pickerModels ---------------------------------------------------

    @Test
    fun pickerIsEmptyWhenNoModelsLoaded() {
        assertEquals(emptyList<SessionModelOption>(), pickerModels(emptyList(), null))
    }

    @Test
    fun pickerSynthesizesExactlyOneDefaultRow() {
        // The live list already carries a default row — none is synthesized,
        // and it is relabeled with what it resolves to (resolvedModel).
        val withResolution = pickerModels(
            listOf(
                model("default", "Default", resolved = "claude-opus-4-8"),
                model("claude-opus-4-8", "Opus 4.1"),
            ),
            null,
        )
        assertEquals(2, withResolution.size)
        assertEquals("CLI Default (Opus 4.1)", withResolution[0].displayName)
        assertTrue(withResolution[0].current == true) // no explicit pick -> default row is current

        // No default row in the source list -> one is prepended, unlabeled
        // resolution -> plain "CLI Default".
        val synthesized = pickerModels(listOf(model("claude-opus-4-8", "Opus 4.1")), null)
        assertEquals("", synthesized[0].value)
        assertEquals("CLI Default", synthesized[0].displayName)
        assertTrue(synthesized[0].current == true)
        assertEquals(2, synthesized.size)
    }

    @Test
    fun pickerKeepsServerCurrentMarkingWhenPresent() {
        val picked = pickerModels(
            listOf(model("default", "Default"), model("claude-sonnet-5", "Sonnet 5", current = true)),
            null,
        )
        assertTrue(picked[0].current != true) // not re-marked: a row already carries current
        assertTrue(picked[1].current == true)
    }

    @Test
    fun pickerResolutionPrefersExactIdMatchOverContainment() {
        // "claude-opus-4-8" must not mislabel as the earlier "claude-opus-4" row.
        val rows = pickerModels(
            listOf(
                model("default", "Default", resolved = "claude-opus-4-8"),
                model("claude-opus-4", "Opus 4"),
                model("claude-opus-4-8", "Opus 4.8"),
            ),
            null,
        )
        assertEquals("CLI Default (Opus 4.8)", rows[0].displayName)
    }

    // --- activeModel ----------------------------------------------------

    @Test
    fun activeModelFallsThroughInOrder() {
        val models = listOf(model("default", "Default"), model("claude-opus-4-8", "Opus 4.1", current = true))
        val picker = pickerModels(models, null)
        assertEquals("Opus 4.1", activeModel(models, picker, "claude-opus-4-8")?.displayName)
        assertEquals("Opus 4.1", activeModel(models, picker, null)?.displayName) // server current
        val noCurrent = listOf(model("default", "Default"))
        assertEquals("CLI Default", activeModel(noCurrent, pickerModels(noCurrent, null), null)?.displayName)
    }

    // --- composerCommandList ---------------------------------------------

    @Test
    fun commandListMergesAdvertisedWithControlsAndGuaranteesModel() {
        val advertised = listOf(
            CliCommand(name = "compact", description = null, argumentHint = null, aliases = null),
            CliCommand(name = "vim", description = "Vim mode", argumentHint = null, aliases = null),
        )
        val controls = listOf(
            SessionCommandOption(name = "compact", description = "Compact the conversation", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(advertised, controls)
        // Advertised wins on membership; description enriched from controls;
        // the closed native allow-set marks only `model` supported; /model is
        // guaranteed and sorts with the supported group first.
        assertEquals(listOf("model", "compact", "vim"), commands.map { it.name })
        assertEquals("Compact the conversation", commands[1].description)
        assertTrue(commands[0].supported)
        assertTrue(!commands[1].supported)
        assertEquals("Switch the model for this session", commands[0].description)
        assertEquals("[model]", commands[0].argumentHint)
    }

    @Test
    fun commandListFallsBackToControlsWhenNothingAdvertised() {
        val controls = listOf(
            SessionCommandOption(name = "model", description = "d", argumentHint = null, aliases = null, supported = true),
            SessionCommandOption(name = "agents", description = "a", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(null, controls)
        assertEquals(listOf("model", "agents"), commands.map { it.name })
        assertTrue(commands.none { it.name == "model" && it.description == "Switch the model for this session" })
    }

    // --- resolveModelArg --------------------------------------------------

    @Test
    fun freeTextModelArgResolvesByExactThenSubstring() {
        val models = listOf(
            model("default", "Default"),
            model("claude-opus-4-8", "Opus 4.8"),
            model("claude-fable-5", "Fable 5"),
        )
        assertEquals("claude-fable-5", resolveModelArg("fable", models)?.value) // displayName substring
        assertEquals("claude-opus-4-8", resolveModelArg("Claude-Opus-4-8", models)?.value) // exact value, any case
        assertEquals("claude-opus-4-8", resolveModelArg("opus", models)?.value)
        assertNull(resolveModelArg("gpt-5", models))
        assertNull(resolveModelArg("   ", models))
    }

    @Test
    fun twoDefaultRowsInSourceAreNotDeduplicated() {
        // Web parity: "" and "default" rows BOTH count as default rows — no
        // synthesis, no dedup; the producer invariant is one row, the function
        // does not enforce it.
        val rows = pickerModels(
            listOf(
                model("", "Default"),
                model("default", "Default"),
                model("claude-opus-4-8", "Opus 4.1"),
            ),
            null,
        )
        assertEquals(3, rows.size)
        assertTrue(rows.count { isDefaultModelRow(it) } == 2)
        assertTrue(rows.any { it.displayName.startsWith("CLI Default") })
    }

    @Test
    fun aliasOfANativeCommandMarksSupported() {
        // The closed allow-set matches by name OR alias; the guaranteed-row
        // check stays name-keyed (web parity), so a /model alias does not
        // suppress the real /model row.
        val controls = listOf(
            SessionCommandOption(name = "m", description = "alias row", argumentHint = null, aliases = listOf("model"), supported = false),
        )
        val commands = composerCommandList(null, controls)
        assertTrue(commands.first { it.name == "m" }.supported)
        assertTrue(commands.any { it.name == "model" })
    }

    @Test
    fun exactDisplayNameBeatsSubstringAcrossSteps() {
        // resolveModelArg's step order: exact value, then exact displayName,
        // THEN substring — an exact displayName on a later row wins over a
        // value-substring on an earlier row.
        val models = listOf(
            model("claude-fable-5-turbo", "Turbo"),
            model("x", "Fable"),
        )
        assertEquals("x", resolveModelArg("fable", models)?.value)
    }
}
