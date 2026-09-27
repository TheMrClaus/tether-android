package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T4.5: the server `log` frame (lib/protocol.ts `{ type: "log"; entries: LogEntry[]; bootId }`,
 * server.mjs recordEventLogEntry) against every recorded frame of the vendored wire corpus, and
 * hand fixtures for what the corpus cannot show (every recorded entry is `info`, and none carries
 * `reason`, `outstanding` or `message`).
 */
class LogWireTest {

    private val corpusDir = File(System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire")

    private fun recordedLogFrames(): List<Pair<String, JsonObject>> =
        corpusDir.listFiles { f -> f.name.endsWith(".jsonl") && f.name != "client-examples.jsonl" }!!
            .sortedBy { it.name }
            .flatMap { file ->
                file.readLines().filter { it.isNotBlank() }
                    .map { TetherJson.parseToJsonElement(it).jsonObject }
                    .filter { it.str("dir") == "s2c" }
                    .map { file.name to it["frame"]!!.jsonObject }
                    .filter { it.second.str("type") == "log" }
            }

    private fun parseLog(json: String): ServerMessage.Log {
        val m = ServerMessage.parse(json)
        assertTrue("$json -> $m", m is ServerMessage.Log)
        return m as ServerMessage.Log
    }

    @Test
    fun everyRecordedLogFrameDecodesEveryEntryFieldForField() {
        val frames = recordedLogFrames()
        assertTrue("corpus has log frames (${frames.size})", frames.size >= 100)
        var entries = 0
        for ((file, raw) in frames) {
            val m = ServerMessage.parse(raw)
            assertTrue("$file: $raw -> $m", m is ServerMessage.Log)
            m as ServerMessage.Log
            assertEquals("$file bootId", raw["bootId"]!!.jsonPrimitive.content, m.bootId)
            val rawEntries = raw["entries"]!!.jsonArray.map { it.jsonObject }
            assertEquals("$file: no entry dropped", rawEntries.size, m.entries.size)
            for ((wire, entry) in rawEntries.zip(m.entries)) {
                entries++
                assertEquals(wire["seq"]!!.jsonPrimitive.longOrNull, entry.seq)
                assertEquals(wire["ts"]!!.jsonPrimitive.longOrNull, entry.ts)
                assertEquals(wire.str("level"), entry.level)
                assertEquals(wire.str("event"), entry.event)
                assertEquals(wire.str("sid"), entry.sid)
                assertEquals(wire.str("turnId"), entry.turnId)
                assertEquals(wire.str("nativeId"), entry.nativeId)
                assertEquals(wire.str("outcome"), entry.outcome)
                assertEquals((wire["durationMs"] as? JsonPrimitive)?.content?.toDouble(), entry.durationMs)
                assertEquals((wire["continuation"] as? JsonPrimitive)?.booleanOrNull == true, entry.continuation)
            }
        }
        assertTrue("decoded $entries entries", entries >= 100)
    }

    @Test
    fun theCorpusCoversTheTurnEventsTheDialogLabels() {
        val events = recordedLogFrames().flatMap { (it.second["entries"]!!.jsonArray).map { e -> e.jsonObject.str("event") } }.toSet()
        // turn.start with continuation:false, turn.end with outcome + durationMs.
        assertTrue(events.containsAll(listOf("turn.start", "turn.end", "ws.connect", "ws.attach")))
        val end = recordedLogFrames().flatMap { it.second["entries"]!!.jsonArray }.map { it.jsonObject }.first { it.str("event") == "turn.end" }
        val decoded = parseLog("""{"type":"log","bootId":1,"entries":[$end]}""").entries.single()
        assertEquals(end.str("outcome"), decoded.outcome)
        assertTrue(decoded.durationMs != null)
    }

    @Test
    fun bootIdIsANumberOnTheWireKeptAsItsText() {
        assertEquals("1767225600000", parseLog("""{"type":"log","bootId":1767225600000,"entries":[]}""").bootId)
    }

    @Test
    fun theEventSpecificFieldsTheDialogPrints() {
        val e = parseLog(
            """{"type":"log","bootId":1,"entries":[{"seq":7,"ts":1767225600000,"level":"warn","event":"session.evict",
               "sid":"s1","reason":"idle","outstanding":2,"message":"evicted after 30m","idleMs":1800000}]}""",
        ).entries.single()
        assertEquals("warn", e.level)
        assertEquals("idle", e.reason)
        assertEquals(2.0, e.outstanding)
        assertEquals("evicted after 30m", e.message)
    }

    @Test
    fun oddlyTypedExtrasDegradeToAbsentAndNeverDropTheEntry() {
        val e = parseLog(
            """{"type":"log","bootId":1,"entries":[{"seq":3,"ts":5,"level":"info","event":"turn.end",
               "reason":42,"outstanding":"2","message":{"x":1},"durationMs":"1500","turnId":null,"sid":7}]}""",
        ).entries.single()
        assertEquals(3L, e.seq)
        assertNull(e.reason)
        assertNull(e.outstanding)
        assertNull(e.message)
        assertNull(e.durationMs)
        assertNull(e.turnId)
        assertNull(e.sid)
    }

    @Test
    fun anEntryWithoutANumericSeqIsDroppedLikeTheWebsDedupeDoes() {
        val m = parseLog("""{"type":"log","bootId":1,"entries":[{"ts":1,"level":"info","event":"a"},{"seq":"2","event":"b"},{"seq":3,"event":"c"},5]}""")
        assertEquals(listOf(3L), m.entries.map { it.seq })
    }

    @Test
    fun aMissingOrNonStringLevelIsNullSoTheDialogCountsItAsAWarning() {
        val m = parseLog("""{"type":"log","bootId":1,"entries":[{"seq":1,"event":"a"},{"seq":2,"level":3,"event":"b"}]}""")
        assertEquals(listOf(null, null), m.entries.map { it.level })
    }

    @Test
    fun outcomeAndContinuationFollowJsTruthiness() {
        val m = parseLog(
            """{"type":"log","bootId":1,"entries":[
               {"seq":1,"event":"turn.end","outcome":"ok","continuation":true},
               {"seq":2,"event":"turn.end","outcome":"","continuation":false},
               {"seq":3,"event":"turn.end","outcome":null,"continuation":0},
               {"seq":4,"event":"turn.start","continuation":1},
               {"seq":5,"event":"turn.start","continuation":"yes"},
               {"seq":6,"event":"turn.start","continuation":{}}]}""",
        )
        assertEquals(listOf("ok", null, null, null, null, null), m.entries.map { it.outcome })
        assertEquals(listOf(true, false, false, true, true, true), m.entries.map { it.continuation })
    }

    @Test
    fun aFrameWithoutBootIdOrEntriesIsUnknownNotAnEmptyLog() {
        assertTrue(ServerMessage.parse("""{"type":"log","entries":[]}""") is ServerMessage.Unknown)
        assertTrue(ServerMessage.parse("""{"type":"log","bootId":1}""") is ServerMessage.Unknown)
    }
}
