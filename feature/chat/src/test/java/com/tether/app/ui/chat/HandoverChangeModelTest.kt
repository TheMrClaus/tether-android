package com.tether.app.ui.chat

import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #301: what the handoff banners draw of `AgentSession.handoverChange`, by the web's own resolved compare (lib/handover-change-view.ts). */
class HandoverChangeModelTest {
    private val effortRows = listOf(ModelVariantOption("high", "High"), ModelVariantOption("xhigh", "Extra high"))
    private val models = listOf(
        SessionModelOption(value = "opus", displayName = "Opus 5.5", resolvedModel = "claude-opus-5-5", variants = effortRows),
        SessionModelOption(value = "fable", displayName = "Fable 5.2", resolvedModel = "claude-fable-5-2", variants = effortRows),
    )

    private fun json(text: String): JsonElement = TetherJson.parseToJsonElement(text)

    private fun view(text: String?, defaultModel: String? = null, defaultEffort: String? = null, provider: String = "claude") =
        HandoverChangeModel.view(text?.let(::json), provider, models, defaultModel, defaultEffort)

    @Test fun absentOrMalformedDrawsNothing() {
        assertNull(view(null))
        assertNull(view("null"))
        assertNull(view("""{"reason":"only words"}"""))
        assertNull(view("""{"model":{"from":"opus"}}"""))
    }

    @Test fun aModelOnlyChangeReadsByDisplayName() {
        val v = view("""{"model":{"from":"opus","to":"fable"}}""")!!
        assertEquals(listOf(HandoverChangeItem("Model", "Opus 5.5", "Fable 5.2")), v.items)
        assertNull(v.reason)
        assertEquals("Model Opus 5.5 to Fable 5.2.", v.spoken)
    }

    @Test fun anEffortOnlyChangeUsesTheRowLabelsThenTheClaudeLabels() {
        val v = view("""{"effort":{"from":"high","to":"xhigh"},"reason":"stuck"}""")!!
        assertEquals(listOf(HandoverChangeItem("Effort", "High", "Extra high")), v.items)
        assertEquals("stuck", v.reason)
        val raw = HandoverChangeModel.view(json("""{"effort":{"from":"low","to":"max"}}"""), "claude", emptyList(), null, null)!!
        assertEquals(HandoverChangeItem("Effort", "Low", "Max"), raw.items.single())
        val other = HandoverChangeModel.view(json("""{"effort":{"from":"low","to":"max"}}"""), "codex", emptyList(), null, null)!!
        assertEquals(HandoverChangeItem("Effort", "low", "max"), other.items.single())
    }

    @Test fun bothChangesModelFirstAndTheReasonReadsLast() {
        val v = view("""{"effort":{"from":"high","to":"xhigh"},"model":{"from":"opus","to":"fable"},"reason":"Stuck on edge cases"}""")!!
        assertEquals(listOf("Model", "Effort"), v.items.map { it.label })
        assertEquals("Model Opus 5.5 to Fable 5.2. Effort High to Extra high. Reason: Stuck on edge cases", v.spoken)
    }

    @Test fun aBlankReasonIsDropped() {
        assertNull(view("""{"model":{"from":"opus","to":"fable"},"reason":"  "}""")!!.reason)
    }

    @Test fun aNullSourceNamesTheResolvedDefaultWhereKnown() {
        val known = view("""{"model":{"from":null,"to":"fable"}}""", defaultModel = "opus")!!
        assertEquals(HandoverChangeItem("Model", "Opus 5.5", "Fable 5.2"), known.items.single())
        val unknown = view("""{"model":{"from":null,"to":"fable"}}""")!!
        assertEquals(HandoverChangeItem("Model", "Default", "Fable 5.2"), unknown.items.single())
    }

    @Test fun resolvedEqualValuesAreNoChangeAndTheReasonGoesWithIt() {
        // The source ran on the default (null); the successor names that same model, by alias or by its full id.
        assertNull(view("""{"model":{"from":null,"to":"opus"},"reason":"why"}""", defaultModel = "opus"))
        assertNull(view("""{"model":{"from":null,"to":"claude-opus-5-5"},"reason":"why"}""", defaultModel = "opus"))
        assertNull(view("""{"model":{"from":"opus","to":"claude-opus-5-5"}}"""))
        assertNull(view("""{"effort":{"from":null,"to":"HIGH"}}""", defaultEffort = "high"))
        // One setting equal, the other changed: only the changed one shows.
        val v = view("""{"model":{"from":null,"to":"opus"},"effort":{"from":"high","to":"xhigh"}}""", defaultModel = "opus")!!
        assertEquals(listOf("Effort"), v.items.map { it.label })
    }

    @Test fun aClearedSettingArrivesAsDefaultAndResolvesToTheDefault() {
        val v = view("""{"model":{"from":"fable","to":"default"}}""", defaultModel = "opus")!!
        assertEquals(HandoverChangeItem("Model", "Fable 5.2", "Opus 5.5"), v.items.single())
        val unknown = view("""{"model":{"from":"fable","to":"default"}}""")!!
        assertEquals(HandoverChangeItem("Model", "Fable 5.2", "Default"), unknown.items.single())
    }
}
