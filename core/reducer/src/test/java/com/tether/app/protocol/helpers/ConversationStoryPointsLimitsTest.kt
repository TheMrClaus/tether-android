package com.tether.app.protocol.helpers

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2.2 decision (logged on the bead): the port is faithful to the web's 220/260 limits, and the
 * Android timeline's deliberate wider-bubble 270/320 must stay expressible as parameters.
 */
class ConversationStoryPointsLimitsTest {

    private fun projection(prompt: String, reply: String) = JsObj.of(
        "turnOrder" to JsArr.of(JsStr("t1")),
        "turnsById" to JsObj.of(
            "t1" to JsObj.of(
                "continuation" to JsBool.FALSE,
                "startedAt" to js(1790078400000L),
                "blocks" to JsArr.of(JsStr("b1"), JsStr("b2")),
                "blocksById" to JsObj.of(
                    "b1" to JsObj.of("blockId" to JsStr("b1"), "kind" to JsStr("user_message"), "text" to JsStr(prompt)),
                    "b2" to JsObj.of("blockId" to JsStr("b2"), "kind" to JsStr("message"), "text" to JsStr(reply)),
                ),
            ),
        ),
    )

    @Test
    fun webLimitsAreTheDefaults() {
        val point = ConversationStoryPoints.storyPointsFromSession(projection("p".repeat(300), "r".repeat(300))).single()
        assertEquals(220, point.prompt.length)
        assertEquals("p".repeat(219) + "…", point.prompt)
        assertEquals(260, point.reply.length)
    }

    @Test
    fun androidTimelineLimitsAreParameters() {
        val point = ConversationStoryPoints.storyPointsFromSession(projection("p".repeat(400), "r".repeat(400)), promptMax = 270, replyMax = 320).single()
        assertEquals(270, point.prompt.length)
        assertEquals(320, point.reply.length)
        assertEquals(1790078400000.0, point.ts)
    }

    @Test
    fun whitespaceCollapsesWithJsSemantics() {
        // JS `\s` / trim include U+00A0 and U+FEFF; Kotlin's `\s` would not.
        val point = ConversationStoryPoints.storyPointsFromSession(projection("﻿ a  \n b ", "x")).single()
        assertEquals("a b", point.prompt)
    }
}
