package com.tether.app.protocol.tree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T6.2 round 3 (security review M2): a value from the wire nests at most [JsCodec.MAX_DEPTH]
 * deep, so a hostile 100k-deep array cannot overflow the reader thread's stack in the fold, the
 * writers, structural equality or the renderers. Checked on a thread with a 1 MB stack (a JVM/Android default thread, like the socket reader).
 */
class JsCodecDepthTest {
    private fun onSmallStack(block: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread(null, { try { block() } catch (t: Throwable) { failure = t } }, "small-stack", 1024L * 1024L)
        thread.start()
        thread.join(20_000)
        failure?.let { throw it }
    }

    private fun depthOf(value: JsValue): Int {
        var d = 0
        var v: JsValue? = value
        while (v is JsArr) {
            d++
            v = v.firstOrNull()
        }
        return d
    }

    private val sm = com.tether.app.protocol.ServerMessage

    @Test fun aHundredThousandDeepFrameIsReadWithItsDeepPartNulled() = onSmallStack {
        val deep = "{\"type\":\"git-diff-file\",\"sessionId\":\"s\",\"path\":\"p\",\"hunks\":\"h\",\"truncated\":false,\"binary\":false,\"x\":" +
            "[".repeat(100_000) + "1" + "]".repeat(100_000) + "}"
        // Not dropped: the frame decodes, its too-deep part is null.
        val message = sm.parse(deep)
        assertEquals(com.tether.app.protocol.ServerMessage.GitDiffFile("s", "p", "h", truncated = false, binary = false), message)
        val objects = "{\"type\":\"x\",\"v\":" + "{\"a\":".repeat(50_000) + "1" + "}".repeat(50_000) + "}"
        val unknown = sm.parse(objects) as com.tether.app.protocol.ServerMessage.Unknown
        assertEquals("x", unknown.type)
    }

    @Test fun theGuardCountsObjectsAndSkipsStringsAndEscapes() {
        assertEquals(true, sm.nestsDeeperThan("[[[", 2))
        assertEquals(false, sm.nestsDeeperThan("[[]]", 2))
        assertEquals(true, sm.nestsDeeperThan("{\"a\":{\"a\":{\"a\":1}}}", 2))
        assertEquals(true, sm.nestsDeeperThan("[{\"a\":[1]}]", 2))
        // Brackets inside strings, escaped quotes and escaped backslashes do not count.
        assertEquals(false, sm.nestsDeeperThan("[\"[[[[\"]", 1))
        assertEquals(false, sm.nestsDeeperThan("[\"\\\"[[[\"]", 1))
        assertEquals(true, sm.nestsDeeperThan("[\"\\\\\",[[]]]", 2))
        assertEquals(false, sm.nestsDeeperThan("\"" + "[".repeat(5_000) + "\\\"\"", 10))
        // The rewrite keeps everything at or above the limit, strings intact.
        assertEquals("[[null],\"[[[\"]", sm.flattenDeeperThan("[[[1,[2]]],\"[[[\"]", 2))
        assertEquals("{\"a\":{\"b\":null,\"c\":\"}\\\"{\"}}", sm.flattenDeeperThan("{\"a\":{\"b\":{\"x\":\"]\"},\"c\":\"}\\\"{\"}}", 2))
        assertEquals("[1]", sm.flattenDeeperThan("[1]", 5))
    }

    @Test fun aFrameAtTheLimitParsesAndItsValueIsCapped() = onSmallStack {
        val depth = com.tether.app.protocol.ServerMessage.MAX_FRAME_DEPTH - 1
        val text = "[".repeat(depth) + "1" + "]".repeat(depth)
        val value = JsCodec.parse(text)
        assertEquals(JsCodec.MAX_DEPTH, depthOf(value))
        // The rest of the tree still works: write, compare, hash.
        val written = JsCodec.stringify(value)
        assertEquals(JsCodec.MAX_DEPTH, written.count { it == '[' })
        assertEquals(value, JsCodec.parse(text))
        value.hashCode()
    }

    @Test fun deepObjectsAreCappedTooAndShallowValuesAreUntouched() = onSmallStack {
        var e: JsonElement = JsonPrimitive("leaf")
        repeat(20_000) { e = kotlinx.serialization.json.JsonObject(mapOf("k" to e)) }
        var v: JsValue? = JsCodec.fromJson(e)
        var d = 0
        while (v is JsObj) {
            d++
            v = v["k"]
        }
        assertEquals(JsCodec.MAX_DEPTH, d)
        assertEquals(JsNull, v)
        val shallow = Json.parseToJsonElement("""{"a":[1,{"b":[2,[3]]}],"c":"x"}""")
        assertEquals(shallow, JsCodec.toJson(JsCodec.fromJson(shallow)))
        assertNull((JsCodec.fromJson(JsonArray(emptyList())) as JsArr).firstOrNull())
    }
}
