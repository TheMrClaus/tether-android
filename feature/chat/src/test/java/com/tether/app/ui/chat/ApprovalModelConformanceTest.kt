package com.tether.app.ui.chat

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsCodec
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.JsValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6.3 conformance: the Kotlin card models against the web's OWN code (denial-target-model.mjs
 * placeDenials / deniedToolInput / lateDenialToolInput / denialTarget, chat-view.tsx denialCopy and
 * buildQuestionAnswers), run once over the reducer corpus's denial states and synthetic edge cases
 * (tools/parity/gen-approval-expectations.mjs → src/test/resources/approvals/expectations.json).
 */
class ApprovalModelConformanceTest {
    private val doc: JsObj by lazy {
        val text = checkNotNull(javaClass.getResource("/approvals/expectations.json")) { "expectations.json missing" }.readText()
        JsCodec.parse(text) as JsObj
    }

    private fun JsValue?.obj() = this as JsObj
    private fun JsValue?.arr() = this as JsArr
    private fun JsValue?.str() = (this as JsStr).value

    private fun same(where: String, expected: JsValue?, actual: JsValue?) =
        assertEquals(where, JsCodec.canonical(expected ?: JsNull), JsCodec.canonical(actual ?: JsNull))

    private fun ids(list: JsValue?): List<String> = list.arr().map { it.obj()["toolId"].str() }

    private fun targetTree(target: DenialTarget?): JsValue =
        target?.let { JsObj.of("label" to JsStr(it.label), "value" to JsStr(it.value)) } ?: JsNull

    @Test fun placementMatchesTheWebForEveryDenialState() {
        val states = doc["states"].arr()
        assertTrue("the corpus must hold denial states", states.size >= 15)
        for (entryValue in states) {
            val entry = entryValue.obj()
            val where = entry["source"].str()
            val state = entry["state"].obj()
            val actual = placeDenials(state)
            val expected = entry["placement"].obj()
            val byTurn = expected["byTurn"].obj()
            assertEquals("$where turns", byTurn.keys, actual.byTurn.keys)
            for ((turnId, slotValue) in byTurn) {
                val slot = slotValue.obj()
                val mine = actual.byTurn.getValue(turnId)
                val byBlock = slot["byBlock"].obj()
                assertEquals("$where $turnId anchors", byBlock.keys, mine.byBlock.keys)
                for ((blockId, list) in byBlock) assertEquals("$where $turnId/$blockId", ids(list), mine.byBlock.getValue(blockId).map { it.toolId })
                assertEquals("$where $turnId trailing", ids(slot["trailing"]), mine.trailing.map { it.toolId })
            }
            assertEquals("$where homeless", ids(expected["homeless"]), actual.homeless.map { it.toolId })

            val turns = state["turnsById"].obj()
            for (d in entry["turnDenials"].arr()) {
                val row = d.obj()
                val turn = turns[row["turnId"].str()] as? JsObj
                val toolId = row["toolId"].str()
                val input = deniedToolInput(turn, toolId)
                same("$where ${row["turnId"].str()}/$toolId input", row["input"], input)
                same("$where $toolId target", row["target"], targetTree(denialTarget(input)))
                val denial = (turn!!["permissionDenials"] as JsArr).map { it.obj() }.first { it["toolId"].str() == toolId }
                assertEquals("$where $toolId copy", row["copy"].str(), denialCopy(denialView(denial)))
            }
            val unattributed = (state["unattributedPermissionDenials"] as? JsArr).orEmpty()
            entry["late"].arr().forEachIndexed { i, d ->
                val row = d.obj()
                val toolId = row["toolId"].str()
                val input = lateDenialToolInput(state, toolId)
                same("$where late $toolId input", row["input"], input)
                same("$where late $toolId target", row["target"], targetTree(denialTarget(input)))
                assertEquals("$where late $toolId copy", row["copy"].str(), denialCopy(denialView(unattributed[i].obj())))
            }
        }
    }

    @Test fun denialCopyMatchesTheWeb() {
        for (entry in doc["denialCopies"].arr()) {
            val row = entry.obj()
            assertEquals("${row["denial"]}", row["copy"].str(), denialCopy(denialView(row["denial"].obj())))
        }
    }

    @Test fun denialTargetMatchesTheWeb() {
        val rows = doc["denialTargets"].arr()
        assertTrue(rows.size >= 15)
        for (entry in rows) {
            val row = entry.obj()
            same("${row["input"]}", row["target"], targetTree(denialTarget(row["input"]?.takeUnless { it === JsNull })))
        }
    }

    /**
     * Round 4: the answer is built by the GUARD (ConsentGuard.buildAnswers) from slot and label
     * indices; the web's buildQuestionAnswers (by text and label) is the oracle.
     */
    @Test fun theGuardsAnswerBuilderMatchesTheWeb() {
        for (entry in doc["questionAnswers"].arr()) {
            val row = entry.obj()
            val promptsJs = row["prompts"].arr()
            val request = JsObj.of("questions" to promptsJs)
            val slots = com.tether.app.client.ConsentGuard.questionSlots(request)
            fun slotOf(text: String) = slots.slotOf[promptsJs.indexOfFirst { it.obj()["question"].str() == text }]
            val picks = row["picks"].obj()
            val other = row["other"].obj()
            val bySlot = (picks.keys + other.keys).distinct().map { text ->
                val slot = slotOf(text)
                com.tether.app.client.ConsentGuard.QuestionPick(
                    slot,
                    (picks[text] as? JsArr).orEmpty().map { slots.labels.getValue(slot).indexOf(it.str()) },
                    // Through the card's field first: like an HTML text input it holds no line break.
                    (other[text] as? JsStr)?.value.orEmpty().replace("\r", "").replace("\n", ""),
                )
            }
            val skipped = row["skipped"].arr().map { slotOf(it.str()) }.toSet()
            val result = checkNotNull(com.tether.app.client.ConsentGuard.buildAnswers(request, bySlot, skipped)) { "$row refused" }
            val actual = JsObj.of(
                "answers" to JsObj.from(result.answers.mapValues { JsStr(it.value) }),
                "response" to result.response?.let(::JsStr),
            )
            same("$row", row["result"], actual)
        }
    }

    private fun JsArr?.orEmpty(): List<JsValue> = this ?: emptyList()
}
