package com.tether.app.protocol.conformance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2.1 source-level guards over core/reducer/.../fold/:
 *  - purity: the fold never reads a clock or a random source (events.ts is pure);
 *  - Revision 10: every `case "…"` label in the vendored events.ts `reduceEvent` is dispatched
 *    explicitly by Reduce.kt, and (reported now, fail-hard once unit I flips [STRICT_DEFAULT] or
 *    under `-Pparity.strict=true`) actually ported in the family file it is dispatched to.
 */
class FoldSourceChecksTest {

    companion object {
        /** Unit I flips this to true: from then on a dispatched-but-unported label fails the build. */
        const val STRICT_DEFAULT = true

        private val strict: Boolean
            get() = System.getProperty("parity.strict")?.toBooleanStrictOrNull() ?: STRICT_DEFAULT
    }

    private val foldDir = File(CanonicalJson.repoRoot, "core/reducer/src/main/java/com/tether/app/protocol/fold")
    private val eventsTs = File(CanonicalJson.corpusDir, "reference/events.ts")

    private fun code(file: File): String =
        file.readLines().joinToString("\n") { line -> line.replace(Regex("//.*$"), "") }

    @Test
    fun foldIsPure() {
        val forbidden = listOf(
            Regex("""\bSystemClock\b"""),
            Regex("""\bRandom\b"""),
            Regex("""\bInstant\s*\.\s*now\b"""),
            Regex("""\bClock\b"""),
            Regex("""\bSystem\s*\.\s*currentTimeMillis\b"""),
            Regex("""\bSystem\s*\.\s*nanoTime\b"""),
            Regex("""\bLocalDateTime\b|\bjava\.util\.Date\b"""),
        )
        val files = foldDir.listFiles { f -> f.name.endsWith(".kt") }!!.toList()
        assertTrue("fold/ not found at $foldDir", files.isNotEmpty())
        val hits = files.flatMap { file ->
            code(file).lines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }
        assertTrue("fold/ must not read a clock or randomness:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /** `case "x":` labels inside `function reduceEvent(state, event) { … }` of the reference. */
    private fun jsLabels(): List<String> {
        val lines = eventsTs.readLines()
        val start = lines.indexOfFirst { it.startsWith("function reduceEvent(") }
        assertTrue("reduceEvent not found in reference events.ts", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "}" }
        val label = Regex("""^\s*case "([^"]+)":""")
        return (start..end).mapNotNull { label.find(lines[it])?.groupValues?.get(1) }
    }

    /** ta-d3tj.10 (R1): events.mjs became events.ts upstream; the label set is exactly the one the 887c222 reference had (74). */
    @Test
    fun theTsReferenceHasExactlyTheLabelSetOfTheOldMjsReference() {
        val old = setOf(
            "native_session_id", "cli_inventory_reset", "cli_commands_changed", "api_retry",
            "rate_limit", "fast_mode", "limit_hit", "rate_limit_resume_scheduled",
            "rate_limit_resume_dismissed", "rate_limit_resume_fired", "todo_updated", "todo_item_created",
            "todo_item_id_assigned", "todo_item_updated", "plan_updated", "diff_updated",
            "model_rerouted", "model_fallback", "review_started", "review_completed",
            "mcp_health_updated", "context_compacted", "task_started", "task_progress",
            "task_completed", "background_tasks_changed", "provider_notice", "permission_denied",
            "turn_started", "user_message_accepted", "message_started", "message_delta",
            "message_completed", "command_output_started", "command_output_delta", "command_output_completed",
            "background_command_updated", "background_command_output", "spawned_run_updated", "spawned_run_output",
            "spawned_run_media", "thinking_delta", "thinking_completed", "thinking_stop",
            "tool_start", "tool_output_delta", "tool_progress", "tool_end",
            "approval_request", "question_request", "question_resolved", "question_cancelled",
            "question_answered", "approval_resolved", "approval_expired", "cancel_requested",
            "cancelled", "process_exit", "usage", "token_progress",
            "turn_activity", "subagent_message", "warning", "unknown_event",
            "error", "turn_end", "background_interrupted", "background_abandoned",
            "turn_interrupted", "external_advancement", "notice_dismissed", "queued_message_added",
            "queued_message_updated", "queued_message_removed",
        )
        assertEquals(74, old.size)
        assertEquals(old, jsLabels().toSet())
    }

    /** Reduce.kt's dispatch: label → family function name. */
    private fun kotlinDispatch(): Map<String, String> {
        val arm = Regex("""^\s*"([^"]+)"\s*->\s*(\w+)\(""")
        return code(File(foldDir, "Reduce.kt")).lines().mapNotNull { arm.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun reduceKtDispatchesEveryReduceEventLabelExplicitly() {
        val js = jsLabels()
        assertEquals("reference reduceEvent should have 74 case labels", 74, js.size)
        val dispatch = kotlinDispatch()
        assertEquals("labels missing from Reduce.kt's when", emptyList<String>(), js.filter { it !in dispatch })
        assertEquals("Reduce.kt dispatches labels events.ts does not have", emptyList<String>(), dispatch.keys.filter { it !in js })
    }

    @Test
    fun everyDispatchedLabelIsPortedInItsFamilyFile() {
        val familyFiles = mapOf(
            "foldTurn" to "FoldTurn.kt",
            "foldInteraction" to "FoldInteraction.kt",
            "foldNotices" to "FoldNotices.kt",
            "foldSessionLists" to "FoldSessionLists.kt",
            "foldBackground" to "FoldBackground.kt",
        )
        val armLabels = Regex("""^\s*((?:"[^"]+"\s*,\s*)*"[^"]+")\s*->""")
        val quoted = Regex(""""([^"]+)"""")
        val ported: Map<String, Set<String>> = familyFiles.mapValues { (_, file) ->
            code(File(foldDir, file)).lines().mapNotNull { armLabels.find(it) }
                .flatMap { m -> quoted.findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSet()
        }
        val unported = kotlinDispatch().filter { (label, fn) -> fn in familyFiles && label !in ported.getValue(fn) }
        val byFamily = unported.entries.groupBy({ familyFiles.getValue(it.value) }, { it.key })
        val report = "event-type labels dispatched but not yet ported (${unported.size}/74):\n" +
            byFamily.entries.joinToString("\n") { (file, labels) -> "  $file (${labels.size}): ${labels.joinToString()}" }
        println(report)
        File("build/parity/label-coverage.txt").apply { parentFile.mkdirs() }.writeText(report + "\n")
        if (strict) assertTrue(report, unported.isEmpty())
    }
}
