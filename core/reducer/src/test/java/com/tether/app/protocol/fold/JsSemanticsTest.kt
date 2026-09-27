package com.tether.app.protocol.fold

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.protocol.tree.js
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.1 H1: the JS-semantics helpers in Js.kt / Limits.kt (plan §4 "own unit tests"). */
class JsSemanticsTest {

    @Test
    fun truthiness() {
        listOf(null, JsNull, js(false), js(0), js(-0.0), js(Double.NaN), js("")).forEach { assertFalse("$it", truthy(it)) }
        listOf(js(true), js(1), js(-1), js("0"), js(" "), JsObj.EMPTY, JsArr.EMPTY).forEach { assertTrue("$it", truthy(it)) }
    }

    @Test
    fun coalesceIsNotOr() {
        assertEquals(js(0), coalesce(js(0), js(5)))
        assertEquals(js(""), coalesce(js(""), js("x")))
        assertEquals(js(5), coalesce(JsNull, js(5)))
        assertEquals(js(5), coalesce(null, js(5)))
        assertEquals(js(5), jsOr(js(0), js(5)))
        assertSame(JsNull, (null as com.tether.app.protocol.tree.JsValue?).orJsNull())
    }

    @Test
    fun strictEqualsSemantics() {
        assertTrue(strictEquals(js(0), js(-0.0)))
        assertFalse(strictEquals(js(Double.NaN), js(Double.NaN)))
        assertFalse(strictEquals(JsNull, null))
        assertTrue(strictEquals(null, null))
        assertFalse(strictEquals(JsObj.of("a" to js(1)), JsObj.of("a" to js(1))))
        val o = JsObj.of("a" to js(1))
        assertTrue(strictEquals(o, o))
        assertFalse(strictEquals(js("1"), js(1)))
    }

    @Test
    fun jsTrimMatchesEcmaScript() {
        assertEquals("x", jsTrim("﻿  \t\n x　  "))
        // Kotlin's trim() strips U+001C..U+001F (isWhitespace); JS does not.
        assertEquals("\u001Cx\u001F", jsTrim("\u001Cx\u001F"))
        assertEquals("", jsTrim("﻿"))
        assertTrue(isBlank(js(" ﻿")))
        assertTrue(isBlank(js(5)))
        assertFalse(isBlank(js("\u001C")))
    }

    @Test
    fun codePointBounding() {
        val emoji = "😀" // one code point, two UTF-16 units
        assertEquals(1, codePointLength(emoji))
        assertEquals(2, codePointLength("\uD800x")) // a lone surrogate counts as one
        assertEquals("a$emoji", boundedDisplayText(js("a${emoji}b"), 2))
        assertEquals("ab", boundedDisplayText(js("ab"), 2))
        assertNull(boundedDisplayText(js(1), 2))
        assertNull(boundedIdentifier(js("")))
        assertEquals("x".repeat(200), boundedIdentifier(js("x".repeat(250))))
        // boundedApprovalChoiceId measures UTF-16 length and rejects rather than truncates.
        assertNull(boundedApprovalChoiceId(js(emoji.repeat(65))))
        assertEquals(emoji.repeat(64), boundedApprovalChoiceId(js(emoji.repeat(64))))
        assertEquals("cmd", identifier(js("  /cmd "), stripLeadingSlash = true))
        assertNull(identifier(js("x".repeat(101))))
        assertNull(identifier(js("   ")))
    }

    @Test
    fun normalizeStringListSkipsAndBounds() {
        val list = normalizeStringList(JsArr.of(js("a"), js(""), js(3), js("bcd"), js("e")), limit = 2, maxChars = 2)!!
        assertEquals(JsArr.of(js("a"), js("bc")), list)
        assertNull(normalizeStringList(js("a"), limit = 2, maxChars = 2))
    }

    @Test
    fun numberPredicates() {
        assertTrue(isInteger(js(3.0)))
        assertFalse(isInteger(js(3.5)))
        assertFalse(isInteger(js(Double.POSITIVE_INFINITY)))
        assertFalse(isInteger(js("3")))
        assertTrue(isFiniteNumber(js(-1.5)))
        assertFalse(isFiniteNumber(js(Double.NaN)))
        assertNull(nonNegativeFiniteNumber(js(-1)))
        assertEquals(js(0), nonNegativeFiniteNumber(js(0)))
        assertEquals(JsObj.EMPTY, blockEventTs(JsObj.of("ts" to js("1"))))
        assertEquals(JsObj.of("ts" to js(5)), blockEventTs(JsObj.of("ts" to js(5))))
    }

    @Test
    fun stringConversionAndDismissKeys() {
        assertEquals("undefined", jsToString(null))
        assertEquals("null", jsToString(JsNull))
        assertEquals("1e+21", jsToString(js(1e21)))
        assertEquals("12", jsToString(js(12.0)))
        assertEquals("background_interrupted:12", backgroundNoticeDismissKey(js("background_interrupted"), js(12)))
        assertEquals("background_interrupted:unsequenced", backgroundNoticeDismissKey(js("background_interrupted"), null))
        assertEquals("provider_notice:session:n1", providerNoticeDismissKey(JsNull, js("n1")))
        assertEquals("provider_notice:t1:n1", providerNoticeDismissKey(js("t1"), js("n1")))
    }

    @Test
    fun jsonStringifyUsesJsKeyOrder() {
        val v = JsObj.of("task" to JsObj.of("id" to js("7")), "2" to js(0.1 + 0.2), "1" to JsStr(" "))
        assertEquals("{\"1\":\" \",\"2\":0.30000000000000004,\"task\":{\"id\":\"7\"}}", jsonStringify(v))
    }

    @Test
    fun initialStateAndDerivedStatus() {
        val s = initialSessionState("s", "claude", "/w")
        assertEquals(JsNull, s["nativeSessionId"])
        assertEquals("ready", deriveSessionStatus(currentTurn(s)))
        assertNull(lastMessageActivityAt(s))
        val t = newTurnProjection(js("t1"), null, startedAt = js(5))
        assertEquals("active", deriveSessionStatus(t))
        assertEquals("waiting", deriveSessionStatus(t.put("pendingQuestions", JsObj.of("q" to JsObj.EMPTY))))
    }
}
