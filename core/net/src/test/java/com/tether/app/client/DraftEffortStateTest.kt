package com.tether.app.client

import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.TetherJson
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #292 (owner ruling D2), the new-session form's Effort control, as lib/draft-form.ts `draftEffortState` /
 * `effortToSend` and draft-composer.tsx: never hidden for a Claude model. Levels as before; a model the server
 * reports as having none (`supportsEffort: false`, or an empty reported list) is present, disabled, with the
 * hint and nothing sent; a model with no data either way (a gap, and an older server that never sends the field)
 * is a Default-only select plus the remembered value, no hint. Other providers keep today's rule (absent).
 */
class DraftEffortStateTest {
    private val levels = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("high", "High"), ModelVariantOption("max", "Max"))
    private val claudeModels = listOf(
        SessionModelOption("opus", "Opus", variants = levels),
        SessionModelOption("haiku", "Haiku", supportsEffort = false),
        SessionModelOption("listed-none", "Listed none", supportedEffortLevels = emptyList()),
        SessionModelOption("unknown", "Unknown"),
    )
    private val claude = ProviderCatalogEntry("claude", "claude", "ready", claudeModels, label = "Claude")
    private val codex = ProviderCatalogEntry("codex", "codex", "ready", listOf(SessionModelOption("gpt-5", "GPT-5")), label = "Codex")

    private fun form(entry: ProviderCatalogEntry, model: String, effort: String = "") =
        DraftForm.INITIAL_DRAFT_FORM.with(
            "key" to JsStr(entry.key), "model" to JsStr(model), "reasoningEffort" to JsStr(effort), "cwd" to JsStr("/w"), "mode" to JsStr("default"),
        )

    private fun options(entry: ProviderCatalogEntry, model: String, effort: String = "") =
        DraftSessionOptionsModel.of(entry, form(entry, model, effort))

    @Test fun levelsAreTodaysControlAndNoHint() {
        val o = options(claude, "opus", "high")
        assertEquals(listOf("low", "high", "max"), o.effort!!.options.map { it.value })
        assertEquals("High", o.effort!!.current!!.label)
        assertNull(o.effortNoneHint)
    }

    @Test fun aKnownNoneModelIsPresentDisabledWithTheHint() {
        for (model in listOf("haiku", "listed-none")) {
            val o = options(claude, model)
            assertNull("$model: no choosable select", o.effort)
            assertEquals("$model: the one hint", "This model has no effort setting", o.effortNoneHint)
            assertTrue("$model: the phone's settings chip still shows", o.hasSettings)
        }
    }

    @Test fun aGapIsADefaultOnlySelectWithNoHint() {
        val o = options(claude, "unknown")
        assertEquals(listOf(""), o.effort!!.options.map { it.value })
        assertEquals("Default", o.effort!!.options.single().label)
        assertNull(o.effortNoneHint)
    }

    @Test fun aGapKeepsTheRememberedValueAsARow() {
        val o = options(claude, "unknown", "high")
        assertEquals(listOf("", "high"), o.effort!!.options.map { it.value })
        assertEquals("high", o.effort!!.value)
        assertNull(o.effortNoneHint)
    }

    @Test fun anOlderServerThatNeverSendsTheFieldIsAGapNotNone() {
        val row = TetherJson.decodeFromString(SessionModelOption.serializer(), """{"value":"haiku","displayName":"Haiku"}""")
        assertNull(row.supportsEffort)
        assertNull(row.supportedEffortLevels)
        val older = claude.copy(models = listOf(row))
        val o = options(older, "haiku")
        assertNotNull("never hidden", o.effort)
        assertNull("no claim without the signal", o.effortNoneHint)
    }

    @Test fun theWireFieldParses() {
        val none = TetherJson.decodeFromString(SessionModelOption.serializer(), """{"value":"haiku","displayName":"Haiku","supportsEffort":false}""")
        assertEquals(false, none.supportsEffort)
        val listed = TetherJson.decodeFromString(SessionModelOption.serializer(), """{"value":"x","displayName":"X","supportedEffortLevels":[]}""")
        assertEquals(emptyList<String>(), listed.supportedEffortLevels)
    }

    @Test fun otherProvidersWithNoVariantsStayAbsent() {
        val o = options(codex, "gpt-5")
        assertNull(o.effort)
        assertNull(o.effortNoneHint)
        assertFalse(o.hasSettings && o.mode == null)
        // supportsEffort never makes a non-Claude row known-none (the web's Claude-only scope).
        val flagged = codex.copy(models = listOf(SessionModelOption("gpt-5", "GPT-5", supportsEffort = false)))
        assertNull(options(flagged, "gpt-5").effortNoneHint)
    }

    @Test fun theRememberedEffortSurvivesAKnownNoneRoundTripAndIsNotSentWhileThere() {
        var state: com.tether.app.protocol.tree.JsValue? = JsObj.of("form" to form(claude, "opus", "high"), "userModified" to DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE))
        fun pick(model: String) {
            state = DraftForm.reduceDraftForm(
                state,
                JsObj.of("type" to JsStr("SET_MODEL_FROM_USER"), "entry" to claude.toDraftEntry(), "modelId" to JsStr(model), "preferences" to JsObj.EMPTY),
            )
        }
        fun effort() = ((state as JsObj)["form"] as JsObj)["reasoningEffort"].let { (it as JsStr).value }
        pick("haiku")
        assertEquals("kept in the form while known-none", "high", effort())
        val f = (state as JsObj)["form"] as JsObj
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        assertNull("not sent while known-none", CreateFrame.build(f.put("cwd", JsStr("/w")), claude, picked, "r").reasoningEffort)
        pick("opus")
        assertEquals("back on a model with levels", "high", effort())
        val back = (state as JsObj)["form"] as JsObj
        assertEquals("sent again", "high", CreateFrame.build(back.put("cwd", JsStr("/w")), claude, picked, "r").reasoningEffort)
    }

    @Test fun theCreateFrameSendsTheGapAndLevelsEffortAsBefore() {
        val picked = DraftForm.INITIAL_USER_MODIFIED.with("reasoningEffort" to JsBool.TRUE)
        assertEquals("high", CreateFrame.build(form(claude, "opus", "high"), claude, picked, "r").reasoningEffort)
        assertEquals("high", CreateFrame.build(form(claude, "unknown", "high"), claude, picked, "r").reasoningEffort)
        assertNull(CreateFrame.build(form(claude, "listed-none", "high"), claude, picked, "r").reasoningEffort)
    }

    @Test fun theHandoverSuccessorSendsNothingForAKnownNoneModel() {
        val built: JsonObject = HandoverDraftForm.buildSuccessor(form(claude, "haiku", "high"), claude, "haiku", "", null, "", false)
        assertFalse("not sent while known-none", built.containsKey("reasoningEffort"))
        val levelsBuilt = HandoverDraftForm.buildSuccessor(form(claude, "opus", "high"), claude, "opus", "", null, "", false)
        assertEquals("high", levelsBuilt["reasoningEffort"]!!.toString().trim('"'))
        // A seed of "high" and a known-none form: the web's effortToSend is "" vs the seed, so the explicit "default" rides.
        val reset = HandoverDraftForm.buildSuccessor(form(claude, "haiku", "high"), claude, "haiku", "high", null, "", false)
        assertEquals("default", reset["reasoningEffort"]!!.toString().trim('"'))
    }

    @Test fun theHelperKeepsTheRequestedEffortForClaudeOnly() {
        val models = claude.toDraftEntry()["models"]
        assertEquals("high", DraftForm.resolveReasoningEffortForModel(models, JsStr("haiku"), JsStr("high"), JsStr("claude")))
        assertEquals("", DraftForm.resolveReasoningEffortForModel(models, JsStr("haiku"), JsStr("high"), JsStr("codex")))
        assertEquals("", DraftForm.resolveReasoningEffortForModel(models, JsStr("haiku"), JsStr("high")))
    }
}
