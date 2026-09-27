package com.tether.app.protocol.conformance

import com.tether.app.protocol.conformance.CanonicalJson.TaggedArray
import com.tether.app.protocol.conformance.CanonicalJson.TaggedDate
import com.tether.app.protocol.conformance.CanonicalJson.TaggedFn
import com.tether.app.protocol.conformance.CanonicalJson.TaggedMap
import com.tether.app.protocol.conformance.CanonicalJson.TaggedObject
import com.tether.app.protocol.conformance.CanonicalJson.TaggedSet
import com.tether.app.protocol.conformance.CanonicalJson.Undefined
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2.2: the helper typed layer (manifest canonicalJson.helperTypedValues), tag by tag, both directions. */
class TaggedValuesTest {

    private fun decode(json: String) = CanonicalJson.decodeTagged(CanonicalJson.parse(json))
    private fun encode(value: Any?) = CanonicalJson.write(CanonicalJson.encodeTagged(value))

    @Test
    fun undefinedIsDistinctFromNull() {
        assertSame(Undefined, decode("""{"${'$'}undefined":true}"""))
        assertSame(JsNull, decode("null"))
        assertNotEquals(decode("null"), decode("""{"${'$'}undefined":true}"""))
        // the adapters' view: undefined is Kotlin null, JS null stays JsNull
        val args = HelperAdapters.Args(listOf(decode("""{"${'$'}undefined":true}"""), decode("null")), null, JsObj.EMPTY)
        assertEquals(null, args.v(0))
        assertSame(JsNull, args.v(1))
        assertEquals(null, args.v(5)) // a missing trailing argument is undefined too
        assertEquals("""{"${'$'}undefined":true}""", encode(Undefined))
        assertEquals("null", encode(null))
        assertEquals("null", encode(JsNull))
    }

    @Test
    fun numbersRoundTrip() {
        for (tag in listOf("NaN", "Infinity", "-Infinity", "-0")) {
            val decoded = decode("""{"${'$'}number":"$tag"}""") as JsNum
            assertEquals("""{"${'$'}number":"$tag"}""", encode(decoded))
        }
        assertTrue((decode("""{"${'$'}number":"NaN"}""") as JsNum).value.isNaN())
        assertEquals(-0.0, (decode("""{"${'$'}number":"-0"}""") as JsNum).value, 0.0)
        assertTrue(1 / (decode("""{"${'$'}number":"-0"}""") as JsNum).value < 0)
        assertEquals("0", encode(JsNum(0.0)))
        assertEquals("1.5", encode(1.5))
    }

    @Test
    fun datesMapsAndSets() {
        assertEquals(TaggedDate(1790078400000.0), decode("""{"${'$'}date":1790078400000}"""))
        assertTrue((decode("""{"${'$'}date":{"${'$'}number":"NaN"}}""") as TaggedDate).ms.isNaN())
        assertEquals("""{"${'$'}date":{"${'$'}number":"NaN"}}""", encode(TaggedDate(Double.NaN)))
        val map = decode("""{"${'$'}map":[["a",1],["b",{"${'$'}undefined":true}]]}""") as TaggedMap
        assertEquals(listOf(JsStr("a") to JsNum(1.0), JsStr("b") to Undefined), map.entries)
        assertEquals("""{"${'$'}map":[["a",1],["b",{"${'$'}undefined":true}]]}""", encode(map))
        assertEquals("""{"${'$'}map":[["t1",2]]}""", encode(linkedMapOf("t1" to 2)))
        val set = decode("""{"${'$'}set":["x","y"]}""") as TaggedSet
        assertEquals(listOf(JsStr("x"), JsStr("y")), set.values)
        assertEquals("""{"${'$'}set":["x","y"]}""", encode(linkedSetOf("x", "y")))
    }

    @Test
    fun functions() {
        val constant = decode("""{"${'$'}fn":"constant","value":false}""") as TaggedFn
        assertSame(JsBool.FALSE, constant(JsStr("anything")))
        val byId = decode("""{"${'$'}fn":"propertyEquals","path":["a","b"],"value":"r8"}""") as TaggedFn
        assertSame(JsBool.TRUE, byId(JsObj.of("a" to JsObj.of("b" to JsStr("r8")))))
        assertSame(JsBool.FALSE, byId(JsObj.of("a" to JsObj.of("b" to JsStr("r9")))))
        assertSame(JsBool.FALSE, byId(null)) // x?.a?.b on undefined
        assertSame(JsBool.FALSE, byId(JsNull))
    }

    @Test
    fun containersWithTaggedMembers() {
        val obj = decode("""{"counts":{"${'$'}fn":"constant","value":true},"enabled":true}""")
        assertTrue(obj is TaggedObject)
        assertTrue((obj as TaggedObject).props["counts"] is TaggedFn)
        val arr = decode("""[1,{"${'$'}undefined":true},null]""") as TaggedArray
        assertEquals(listOf(JsNum(1.0), Undefined, JsNull), arr.items)
        assertEquals("""[1,{"${'$'}undefined":true},null]""", encode(arr))
        // an undefined PROPERTY is omitted on encode, like JSON.stringify
        assertEquals("""{"a":1}""", encode(TaggedObject(mapOf("a" to 1, "b" to Undefined))))
        // plain JSON stays a plain tree (identity), numbers numeric
        val plain = CanonicalJson.parse("""{"a":[1,"x",null,true]}""")
        assertSame(plain, CanonicalJson.decodeTagged(plain))
        assertEquals(JsArr.of(JsNum(1.0)), decode("[1]"))
    }
}
