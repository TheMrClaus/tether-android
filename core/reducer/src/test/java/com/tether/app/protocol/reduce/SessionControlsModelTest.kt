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
            CliCommand(name = "exit", description = "Exit", argumentHint = null, aliases = null),
        )
        val controls = listOf(
            SessionCommandOption(name = "compact", description = "Compact the conversation", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(advertised, controls)
        // T7.3 (chat-view.tsx:244-274, v128): advertised wins on membership; the description is
        // enriched from controls; every advertised command is supported except the blocked ones
        // (exit / stop), which sort last; /model is guaranteed.
        assertEquals(listOf("compact", "model", "vim", "exit"), commands.map { it.name })
        assertEquals("Compact the conversation", commands[0].description)
        assertTrue(commands.take(3).all { it.supported })
        assertTrue(!commands[3].supported)
        val model = commands.first { it.name == "model" }
        assertEquals("Switch the model for this session", model.description)
        assertEquals("[model]", model.argumentHint)
    }

    @Test
    fun commandListFallsBackToControlsWhenNothingAdvertised() {
        val controls = listOf(
            SessionCommandOption(name = "model", description = "d", argumentHint = null, aliases = null, supported = true),
            SessionCommandOption(name = "agents", description = "a", argumentHint = null, aliases = null, supported = false),
        )
        val commands = composerCommandList(null, controls)
        // Both dispatchable (neither is blocked), so by name.
        assertEquals(listOf("agents", "model"), commands.map { it.name })
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
    fun anAliasOfABlockedCommandMarksItUnsupported() {
        // chat-view.tsx:253-259: blocked by name OR alias; the guaranteed-row check stays name-keyed,
        // so an alias does not suppress the real /model row.
        val controls = listOf(
            SessionCommandOption(name = "quit", description = "q", argumentHint = null, aliases = listOf("exit"), supported = true),
            SessionCommandOption(name = "m", description = "alias row", argumentHint = null, aliases = listOf("model"), supported = false),
        )
        val commands = composerCommandList(null, controls)
        assertTrue(!commands.first { it.name == "quit" }.supported)
        assertTrue(commands.first { it.name == "m" }.supported)
        assertTrue(commands.any { it.name == "model" })
        assertEquals("quit", commands.last().name)
    }

    @Test
    fun theCollatorOrdersNamesAsLocaleCompare() {
        val advertised = listOf("Zed", "alpha", "beta").map { CliCommand(name = it) }
        val ci = com.tether.app.protocol.helpers.JsCollator { a, b -> a.lowercase().compareTo(b.lowercase()) }
        assertEquals(listOf("alpha", "beta", "model", "Zed"), composerCommandList(advertised, emptyList(), ci).map { it.name })
        assertEquals(listOf("Zed", "alpha", "beta", "model"), composerCommandList(advertised, emptyList()).map { it.name })
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
