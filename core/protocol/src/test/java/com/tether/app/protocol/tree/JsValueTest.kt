package com.tether.app.protocol.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JsValueTest {

    @Test
    fun putNullRemovesAndJsNullIsKept() {
        val o = JsObj.of("a" to js(1), "b" to JsNull)
        assertTrue(o.has("b"))
        assertEquals(JsNull, o["b"])
        val removed = o.put("b", null)
        assertFalse(removed.has("b"))
        assertNull(removed["b"])
        assertSame(removed, removed.put("zzz", null))
        assertFalse(JsObj.of("x" to null).has("x"))
    }

    @Test
    fun insertionOrderLikeJsSpread() {
        val o = JsObj.of("b" to js(1), "a" to js(2), "c" to js(3))
        assertEquals(listOf("b", "a", "c"), o.keys.toList())
        // Updating an existing key keeps its slot; delete + re-add moves it to the end.
        assertEquals(listOf("b", "a", "c"), o.put("a", js(9)).keys.toList())
        assertEquals(listOf("b", "c", "a"), o.remove("a").put("a", js(9)).keys.toList())
        assertEquals(listOf("b", "a", "c", "d"), o.spread(JsObj.of("a" to js(0), "d" to js(4))).keys.toList())
    }

    @Test
    fun identityPreservedWhenNothingChanges() {
        val inner = JsObj.of("x" to js("y"))
        val o = JsObj.of("inner" to inner, "n" to js(1), "s" to js("t"))
        assertSame(o, o.put("inner", inner))
        assertSame(o, o.put("n", js(1.0)))
        assertSame(o, o.put("s", js("t")))
        assertSame(o, o.remove("missing"))
        // A structurally equal but distinct object is a real write (JS would build a new object).
        val o2 = o.put("inner", JsObj.of("x" to js("y")))
        assertNotSame(o, o2)
        assertEquals(o, o2)
    }

    @Test
    fun numericEquality() {
        assertEquals(js(0.0), js(-0.0))
        assertEquals(js(0.0).hashCode(), js(-0.0).hashCode())
        assertEquals(js(1), js(1.0))
        assertNotEquals(js(1), js("1"))
        assertEquals(JsObj.of("a" to js(-0.0)), JsObj.of("a" to js(0)))
    }

    @Test
    fun arraySliceClampsLikeJs() {
        val a = JsArr.of(js(1), js(2), js(3), js(4))
        assertEquals(JsArr.of(js(3), js(4)), a.slice(-2))
        assertSame(a, a.slice(-10))
        assertSame(JsArr.EMPTY, a.slice(3, 1))
        assertEquals(JsArr.of(js(2)), a.slice(1, 2))
    }

    @Test
    fun canonicalWriterSortsKeysAndEscapes() {
        val v = JsObj.of(
            "b" to js("q\"\\\b\u000C\n\r\t\u0001\u2028é\uD83D\uDE00\uD800x"),
            "a" to JsArr.of(js(1e21), js(-0.0), js(0.5), JsNull, js(true)),
            "B" to JsObj.EMPTY,
        )
        assertEquals(
            "{\"B\":{},\"a\":[1e+21,0,0.5,null,true],\"b\":\"q\\\"\\\\\\b\\f\\n\\r\\t\\u0001\u2028é\uD83D\uDE00\\ud800x\"}",
            JsCodec.canonical(v),
        )
    }

    @Test
    fun stringifyUsesJsPropertyOrder() {
        val v = JsObj.of("b" to js(1), "10" to js(2), "a" to js(3), "2" to js(4), "01" to js(5))
        assertEquals("{\"2\":4,\"10\":2,\"b\":1,\"a\":3,\"01\":5}", JsCodec.stringify(v))
    }

    @Test
    fun parseRoundTripsThroughJsonElement() {
        val text = "{\"a\":[1,2.5,\"x\",null,false,{\"z\":{}}],\"n\":1790000001000,\"u\":\"\\ud800\"}"
        val v = JsCodec.parse(text)
        assertEquals(text, JsCodec.canonical(v))
        assertEquals(v, JsCodec.fromJson(JsCodec.toJson(v)))
    }

    private fun assertNotSame(a: Any, b: Any) = assertFalse(a === b)
}
