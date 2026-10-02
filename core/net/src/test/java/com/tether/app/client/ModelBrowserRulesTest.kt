package com.tether.app.client

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ta-2uq / ta-coik.4: the custom model id rule, the web's (lib/draft-form.ts addCustomModelPref, model-browser.tsx:530-532). */
class ModelBrowserRulesTest {

    /**
     * Positive: any id that is not empty after the ECMAScript trim is accepted, including the ones the
     * retired app rule refused (past 200 bytes, more than one word, hidden characters, the legacy
     * group sentinel). Negative: an empty or blank id is refused, as the web disables its +.
     */
    @Test
    fun aCustomIdIsAnyTrimmedNonEmptyString() {
        val accepted = listOf(
            "claude-opus-4-5[1m]", "anthropic/claude-sonnet-4", "模型-1", "  trimmed  ",
            "é".repeat(100) + "x", "a b", "a\tb", "x‮y", "​x", "x y", LEGACY_GROUP_VALUE,
        )
        for (ok in accepted) assertNull("accepted: ${ok.map { it.code.toString(16) }}", CustomModelId.problem(ok))
        assertEquals("trimmed", CustomModelId.normalize("  trimmed \n"))
        assertEquals("ECMAScript trim takes U+FEFF and U+3000 too", "x", CustomModelId.normalize("﻿　x　"))
        for (blank in listOf("", "   ", "\n\t", "　﻿")) assertEquals(CustomModelId.Problem.Empty, CustomModelId.problem(blank))
    }

    /** The stored ids read as the web's merge reads them: strings, trimmed, empty and repeated ones skipped, no cap. */
    @Test
    fun storedCustomIdsReadAsTheWebReadsThem() {
        val raw = JsObj.of(
            "claude" to JsArr.of(JsStr(" a "), JsStr("a"), JsStr("b c"), JsStr(""), JsNum(1.0), JsStr("ok")),
            "" to JsArr.of(JsStr("x")),
            "codex" to JsArr.of(JsStr("‮")),
            "pi" to JsStr("not a list"),
        )
        assertEquals(mapOf("claude" to listOf("a", "b c", "ok"), "" to listOf("x"), "codex" to listOf("‮")), CustomModelId.asMap(raw))
        assertEquals(emptyMap<String, List<String>>(), CustomModelId.asMap(JsStr("nope")))
        val many = JsObj.of("claude" to JsArr.of((1..500).map { JsStr("m$it") }))
        assertEquals(500, CustomModelId.asMap(many).getValue("claude").size)
    }
}
