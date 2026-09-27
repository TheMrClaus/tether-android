package com.tether.app.protocol.conformance

import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNull
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsValue
import com.tether.app.protocol.tree.str
import java.io.File

/** One reducer corpus case (parity-corpus/reducer/<name>.json), decoded exactly as stored. */
class CorpusCase(
    val name: String,
    val sourceKind: String,
    val initial: JsObj,
    val expectedInitialProjection: JsObj,
    /** Fed to reduce() as stored: journal stamps included, never re-stamped. */
    val inputEvents: List<JsObj>,
    /** One entry per input event; null where the sampling rule skipped the step. */
    val expectedAfterEachStep: List<JsValue?>,
) {
    companion object {
        val reducerDir: File get() = File(CanonicalJson.corpusDir, "reducer")

        fun names(): List<String> =
            reducerDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()

        fun load(name: String): CorpusCase {
            val root = CanonicalJson.read(File(reducerDir, "$name.json")) as JsObj
            val steps = root["expectedProjectionAfterEachStep"] as JsArr
            return CorpusCase(
                name = root["name"].str ?: name,
                sourceKind = (root["source"] as? JsObj)?.get("kind").str ?: "?",
                initial = root["initial"] as JsObj,
                expectedInitialProjection = root["expectedInitialProjection"] as JsObj,
                inputEvents = (root["inputEvents"] as JsArr).map { it as JsObj },
                expectedAfterEachStep = steps.map { if (it === JsNull) null else it },
            )
        }
    }
}

/** conformance/units.txt: case → unit (+ ready/pending) and events.mjs line ranges → unit. */
class ConformanceUnits(val cases: List<CaseEntry>, val ranges: List<RangeEntry>) {
    data class CaseEntry(val name: String, val unit: String, val ready: Boolean)
    data class RangeEntry(val unit: String, val from: Int, val to: Int)

    val byName: Map<String, CaseEntry> by lazy { cases.associateBy { it.name } }

    companion object {
        val UNITS = listOf("H1", "A", "B", "C", "I")

        fun load(): ConformanceUnits {
            val text = ConformanceUnits::class.java.getResourceAsStream("/conformance/units.txt")!!
                .bufferedReader().readText()
            val cases = ArrayList<CaseEntry>()
            val ranges = ArrayList<RangeEntry>()
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                when (parts[0]) {
                    "case" -> {
                        require(parts.size == 4 && parts[2] in UNITS && parts[3] in setOf("ready", "pending")) {
                            "units.txt:${index + 1}: malformed case line: $raw"
                        }
                        cases.add(CaseEntry(parts[1], parts[2], parts[3] == "ready"))
                    }
                    "range" -> {
                        val bounds = parts.getOrNull(2)?.split('-')
                        require(parts.size == 3 && parts[1] in UNITS && bounds?.size == 2) {
                            "units.txt:${index + 1}: malformed range line: $raw"
                        }
                        ranges.add(RangeEntry(parts[1], bounds[0].toInt(), bounds[1].toInt()))
                    }
                    else -> error("units.txt:${index + 1}: unknown directive: $raw")
                }
            }
            return ConformanceUnits(cases, ranges)
        }
    }
}
