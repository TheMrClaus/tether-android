package com.tether.app.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * PLAN §5.2: the vendored wire corpus (tether S0.3, captured at v128) against the
 * v129 Kotlin wire types.
 *  (a) every s2c frame of every scenario decodes to a KNOWN ServerMessage subtype;
 *  (b) every client example decodes and re-encodes to canonically-equal JSON;
 *  (c) the Kotlin discriminator sets equal the TS unions' sets.
 */
class WireConformanceTest {

    private val corpusDir: File = File(
        System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire",
    )
    private val matrixFile: File = File(
        System.getProperty("tether.parityMatrix") ?: "../../docs/parity/matrix.json",
    )

    private fun jsonl(file: File): List<JsonObject> =
        file.readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun scenarioFiles(): List<File> =
        corpusDir.listFiles { f -> f.name.endsWith(".jsonl") && f.name != "client-examples.jsonl" }!!
            .sortedBy { it.name }

    private fun serverFrames(): List<Pair<String, JsonObject>> = scenarioFiles().flatMap { file ->
        jsonl(file).filter { it.str("dir") == "s2c" }.map { file.name to it["frame"]!!.jsonObject }
    }

    @Test
    fun corpusIsPresent() {
        assertTrue("parity corpus missing at ${corpusDir.absolutePath}", corpusDir.isDirectory)
        assertTrue(scenarioFiles().size >= 20)
    }

    // (a) --------------------------------------------------------------------------

    @Test
    fun everyServerFrameDecodesToAKnownType() {
        val frames = serverFrames()
        assertTrue(frames.isNotEmpty())
        val counts = sortedMapOf<String, Int>()
        val subtypes = sortedMapOf<String, String>()
        val failures = mutableListOf<String>()
        var projections = 0
        var stateful = 0
        for ((file, frame) in frames) {
            val type = frame.str("type") ?: "<none>"
            when (val decoded = ServerMessage.parse(frame.toString())) {
                is ServerMessage.Unknown -> failures += "$file: $type -> Unknown(${decoded.reason})"
                else -> {
                    counts[type] = (counts[type] ?: 0) + 1
                    subtypes[type] = decoded::class.simpleName!!
                    if (decoded is ServerMessage.Snapshot && decoded.hasState) {
                        stateful++
                        if (decoded.projection != null) projections++
                    }
                }
            }
        }
        println("WireConformance (a): ${frames.size} s2c frames from ${scenarioFiles().size} files")
        println(String.format("  %-26s %-24s %5s", "type", "Kotlin subtype", "count"))
        for ((type, n) in counts) println(String.format("  %-26s %-24s %5d", type, subtypes[type], n))
        println("  snapshots with state: $stateful, typed projection decoded: $projections")
        assertEquals("Unknown frames:\n" + failures.joinToString("\n"), 0, failures.size)
        assertEquals(frames.size, counts.values.sum())
    }

    /** Every TS server type decodes to a known subtype: from the corpus, or a hand fixture for the unsent ones. */
    @Test
    fun everyServerTypeHasADecodedExample() {
        val seen = serverFrames().mapNotNull { it.second.str("type") }.toSet()
        val covered = seen + ServerFixtures.HAND_AUTHORED.keys
        assertEquals(WireTypeLists.SERVER_TYPES, covered)
        for ((type, text) in ServerFixtures.HAND_AUTHORED) {
            val decoded = ServerMessage.parse(text)
            assertTrue("$type fixture -> $decoded", decoded !is ServerMessage.Unknown)
        }
    }

    @Test
    fun manifestAgreesWithCorpus() {
        val manifest = TetherJson.parseToJsonElement(File(corpusDir, "manifest.json").readText()).jsonObject
        val seen = manifest["serverTypesSeen"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val missing = manifest["serverTypesMissing"]!!.jsonArray.map { it.jsonObject.str("type")!! }.toSet()
        assertEquals(serverFrames().mapNotNull { it.second.str("type") }.toSet(), seen)
        assertEquals(WireTypeLists.SERVER_TYPES, seen + missing)
        assertEquals(missing, ServerFixtures.HAND_AUTHORED.keys)
    }

    // (b) --------------------------------------------------------------------------

    @Test
    fun everyClientExampleRoundTrips() {
        val lines = jsonl(File(corpusDir, "client-examples.jsonl"))
        assertEquals(69, lines.size)
        val failures = mutableListOf<String>()
        val types = sortedSetOf<String>()
        for (line in lines) {
            val frame = line["frame"]!!.jsonObject
            val label = line.str("type") + (line.str("variant")?.let { "/$it" } ?: "")
            val decoded = ClientMessage.decode(frame)
            if (decoded.isFailure) {
                failures += "$label: decode failed: ${decoded.exceptionOrNull()?.message}"
                continue
            }
            val encoded = decoded.getOrThrow().toJsonObject()
            if (canonical(encoded) != canonical(frame)) {
                failures += "$label:\n  want ${canonical(frame)}\n  got  ${canonical(encoded)}"
            }
            types += frame.str("type")!!
        }
        println("WireConformance (b): ${lines.size - failures.size}/${lines.size} client examples round-tripped, ${types.size} types")
        assertEquals("Round-trip failures:\n" + failures.joinToString("\n"), 0, failures.size)
        assertEquals(WireTypeLists.CLIENT_TYPES, types)
    }

    // (c) --------------------------------------------------------------------------

    @Test
    fun kotlinDiscriminatorsEqualTheCheckedInLists() {
        assertEquals(WireTypeLists.SERVER_TYPES, ServerMessage.TYPES)
        assertEquals(WireTypeLists.CLIENT_TYPES, ClientMessage.TYPES)
        println("WireConformance (c): ${ServerMessage.TYPES.size} server / ${ClientMessage.TYPES.size} client types")
    }

    @Test
    fun checkedInListsEqualTheParityMatrix() {
        assertTrue("matrix missing at ${matrixFile.absolutePath}", matrixFile.isFile)
        val rows = TetherJson.parseToJsonElement(matrixFile.readText()).jsonObject["rows"]!!.jsonArray
        fun kind(k: String) = rows.map { it.jsonObject }.filter { it.str("kind") == k }.map { it.str("artifact")!! }.toSet()
        assertEquals(kind("server-msg"), WireTypeLists.SERVER_TYPES)
        assertEquals(kind("client-msg"), WireTypeLists.CLIENT_TYPES)
    }

    /** Optional: parse the live TS unions when TETHER_PROTOCOL_TS names lib/protocol.ts. */
    @Test
    fun checkedInListsEqualTheTsUnions() {
        val path = System.getenv("TETHER_PROTOCOL_TS")
        assumeTrue("TETHER_PROTOCOL_TS not set", !path.isNullOrBlank())
        val src = File(path!!).readText()
        fun union(name: String): Set<String> {
            val start = src.indexOf("export type $name =")
            assertTrue("no union $name in $path", start >= 0)
            val end = src.indexOf("\nexport ", start + 10).let { if (it < 0) src.length else it }
            return Regex("""\|\s*\{\s*type:\s*"([^"]+)"""").findAll(src.substring(start, end)).map { it.groupValues[1] }.toSet()
        }
        assertEquals(union("ServerMessage"), WireTypeLists.SERVER_TYPES)
        assertEquals(union("ClientMessage"), WireTypeLists.CLIENT_TYPES)
        assertTrue(Regex("""export const PROTOCOL_VERSION = $TARGET_PROTOCOL_VERSION;""").containsMatchIn(src))
        assertTrue(Regex("""export const NATIVE_PROTOCOL_FLOOR = $NATIVE_PROTOCOL_FLOOR;""").containsMatchIn(src))
    }

    /**
     * Optional: every re-encoded client example passes the REAL server validator,
     * and equals its normalized output. Needs `node` and TETHER_PROTOCOL_VALIDATE
     * naming lib/protocol-validate.mjs (CI has no tether checkout: skipped).
     */
    @Test
    fun encodedClientMessagesPassProtocolValidate() {
        val validator = System.getenv("TETHER_PROTOCOL_VALIDATE")
        assumeTrue("TETHER_PROTOCOL_VALIDATE not set", !validator.isNullOrBlank())
        val encoded = jsonl(File(corpusDir, "client-examples.jsonl")).map {
            ClientMessage.decode(it["frame"]!!.jsonObject).getOrThrow().encode()
        } + extraClientFrames().map { it.encode() }
        val input = File.createTempFile("t11-client", ".jsonl").apply { writeText(encoded.joinToString("\n")); deleteOnExit() }
        val script = """
            import { validateClientMessage } from ${quote(File(validator!!).toURI().toString())};
            import { readFileSync } from "node:fs";
            const sort = (v) => Array.isArray(v) ? v.map(sort) : (v && typeof v === "object")
              ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, sort(v[k])])) : v;
            let bad = 0, n = 0;
            for (const line of readFileSync(process.argv[1], "utf8").split("\n").filter(Boolean)) {
              n++;
              const frame = JSON.parse(line);
              const r = validateClientMessage(frame);
              const norm = r.ok ? JSON.stringify(sort(JSON.parse(JSON.stringify(r.value)))) : null;
              if (!r.ok) { bad++; console.log("REJECT " + frame.type + ": " + r.error); }
              else if (norm !== JSON.stringify(sort(frame))) { bad++; console.log("NORMALIZED " + frame.type + ": " + norm); }
            }
            console.log("validated " + n + ", bad " + bad);
            process.exit(bad ? 1 : 0);
        """.trimIndent()
        val proc = ProcessBuilder("node", "--input-type=module", "-e", script, input.absolutePath)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("node timed out", proc.waitFor(60, TimeUnit.SECONDS))
        println("protocol-validate.mjs: " + out.trim())
        assertEquals(out, 0, proc.exitValue())
    }

    /** Hand-built frames for encoder paths the corpus examples do not exercise. */
    private fun extraClientFrames(): List<ClientMessage> = listOf(
        ClientMessage.Hello(TARGET_PROTOCOL_VERSION, HELLO_CLIENT_ANDROID),
        ClientMessage.Create(provider = "codex", approvalPolicy = OrNull("never"), approvalsReviewer = OrNull(null)),
        ClientMessage.Create(provider = "codex", worktree = WorktreeCreateRequest(mode = "checkout-pr", prNumber = 12)),
        ClientMessage.Send("s1", "hi", "k1"),
        ClientMessage.Browse(),
        ClientMessage.Attach("s1"),
        ClientMessage.SetAdvancedSettings("2.1.0"),
        ClientMessage.RefreshProviders(),
        ClientMessage.Approval("s1", "r1", decision = "deny"),
        ClientMessage.Question("s1", "r1", mapOf("Q" to "A")),
    )

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Sorted-key JSON text: key order is not significant on the wire. */
        fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { k -> quoteKey(k) + ":" + canonical(e[k]!!) }
            is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            else -> e.toString()
        }

        private fun quoteKey(k: String) = kotlinx.serialization.json.JsonPrimitive(k).toString()
    }
}
