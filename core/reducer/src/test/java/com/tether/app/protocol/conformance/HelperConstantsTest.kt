package com.tether.app.protocol.conformance

import com.tether.app.protocol.conformance.CanonicalJson.TaggedObject
import com.tether.app.protocol.helpers.AttachmentDraft
import com.tether.app.protocol.helpers.ChatViewModelCommand
import com.tether.app.protocol.helpers.CodexModePresets
import com.tether.app.protocol.helpers.DeepseekPeak
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.helpers.DraftPreferences
import com.tether.app.protocol.helpers.Format
import com.tether.app.protocol.helpers.HistoryOrigin
import com.tether.app.protocol.helpers.ModelPicker
import com.tether.app.protocol.helpers.PanelWidths
import com.tether.app.protocol.helpers.PendingInput
import com.tether.app.protocol.helpers.PendingWorkspace
import com.tether.app.protocol.helpers.SpawnMarker
import com.tether.app.protocol.helpers.SpinnerWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * T2.2: every exported constant a helper table records (`constants`) equals its Kotlin port —
 * the catalogs, copy tables, limits and initial states the helpers read. One test per table.
 */
@RunWith(Parameterized::class)
class HelperConstantsTest(private val table: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun tables(): List<Array<Any>> = HelperTable.names().filter { HelperTable.load(it).constants.isNotEmpty() }.map { arrayOf<Any>(it) }

        /**
         * Keys that are exporter INPUT fixtures rather than exported constants: chat-view's model
         * list is the session model list the resolveModelArg cases run against (see the table's notes).
         */
        private val FIXTURE_KEYS = mapOf("chat-view-model-command" to setOf("resolveModelArgModels"))

        private fun obj(vararg entries: Pair<String, Any?>) = TaggedObject(linkedMapOf(*entries))

        /** A Kotlin regex written the JS way (`/…/`): Java's `\z` is the non-multiline JS `$`. */
        private fun jsRegexSource(regex: Regex) = "/" + regex.pattern.replace("\\z", "$") + "/"

        private val KOTLIN: Map<String, Map<String, Any?>> = mapOf(
            "attachment-draft" to mapOf(
                "MAX_ATTACHMENTS" to AttachmentDraft.MAX_ATTACHMENTS,
                "MAX_ATTACHMENT_BYTES" to AttachmentDraft.MAX_ATTACHMENT_BYTES,
                "MAX_ATTACHMENTS_TOTAL_BYTES" to AttachmentDraft.MAX_ATTACHMENTS_TOTAL_BYTES,
            ),
            "chat-view-model-command" to mapOf(
                "TETHER_NATIVE_COMMANDS" to ChatViewModelCommand.TETHER_NATIVE_COMMANDS.toList(),
                "looksLikeModelIdRegex" to jsRegexSource(ChatViewModelCommand.LOOKS_LIKE_MODEL_ID),
            ),
            "codex-mode-presets" to mapOf(
                "CODEX_DEFAULT_MODE" to CodexModePresets.CODEX_DEFAULT_MODE,
                "CODEX_MODE_OPTIONS" to CodexModePresets.CODEX_MODE_OPTIONS,
            ),
            "deepseek-peak" to mapOf(
                "DEEPSEEK_API_PROVIDERS" to DeepseekPeak.DEEPSEEK_API_PROVIDERS,
                "DEEPSEEK_HOLIDAYS" to DeepseekPeak.DEEPSEEK_HOLIDAYS,
                "DEEPSEEK_HOLIDAY_BREAKS" to DeepseekPeak.DEEPSEEK_HOLIDAY_BREAKS.map { obj("name" to it.name, "zh" to it.zh, "dates" to it.dates) },
                "DEEPSEEK_HOLIDAY_YEARS" to DeepseekPeak.DEEPSEEK_HOLIDAY_YEARS,
                "DEEPSEEK_PEAK_IMMINENT_MS" to DeepseekPeak.DEEPSEEK_PEAK_IMMINENT_MS,
                "DEEPSEEK_RATES" to DeepseekPeak.DEEPSEEK_RATES,
                "DEEPSEEK_RATE_ROWS" to DeepseekPeak.DEEPSEEK_RATE_ROWS,
                "DSH_DEFAULT_DEEPSEEK_MODEL" to DeepseekPeak.DSH_DEFAULT_DEEPSEEK_MODEL,
                "PEAK_WINDOWS_UTC" to DeepseekPeak.PEAK_WINDOWS_UTC.map { listOf(it.first, it.second) },
            ),
            "draft-form" to mapOf(
                "CLAUDE_DEFAULT_PERMISSION_MODE" to DraftForm.CLAUDE_DEFAULT_PERMISSION_MODE,
                "INITIAL_DRAFT_FORM" to DraftForm.INITIAL_DRAFT_FORM,
                "INITIAL_DRAFT_FORM_STORE" to DraftForm.INITIAL_DRAFT_FORM_STORE,
                "INITIAL_USER_MODIFIED" to DraftForm.INITIAL_USER_MODIFIED,
            ),
            "draft-preferences" to mapOf("DRAFT_PREFS_KEY" to DraftPreferences.DRAFT_PREFS_KEY),
            "format" to mapOf("statusCopy" to TaggedObject(Format.statusCopy)),
            "history-origin" to mapOf("HISTORY_ORIGINS" to HistoryOrigin.HISTORY_ORIGINS),
            "model-picker" to mapOf(
                "CLAUDE_MODEL_CATALOG" to ModelPicker.CLAUDE_MODEL_CATALOG.map { m ->
                    TaggedObject(
                        linkedMapOf<String, Any?>("value" to m.value, "displayName" to m.displayName, "description" to m.description)
                            .also { out ->
                                m.aliases?.let { out["aliases"] = it }
                                m.reviewAfter?.let { out["reviewAfter"] = it }
                                m.retiresOn?.let { out["retiresOn"] = it }
                            },
                    )
                },
                "LEGACY_GROUP_VALUE" to ModelPicker.LEGACY_GROUP_VALUE,
            ),
            "panel-widths" to mapOf(
                "PANEL_WIDTH_LIMITS" to TaggedObject(
                    PanelWidths.PANEL_WIDTH_LIMITS.mapValues { (_, l) -> obj("minRem" to l.minRem, "maxRem" to l.maxRem, "maxVw" to l.maxVw) },
                ),
                "PANEL_WIDTH_VARS" to TaggedObject(PanelWidths.PANEL_WIDTH_VARS),
                "PANEL_WIDTH_STEP_PX" to PanelWidths.PANEL_WIDTH_STEP_PX,
                "PANEL_WIDTH_LARGE_STEP_PX" to PanelWidths.PANEL_WIDTH_LARGE_STEP_PX,
            ),
            "pending-input" to mapOf(
                "MAX_TRIES" to PendingInput.MAX_TRIES,
                "MAX_AGE_MS" to PendingInput.MAX_AGE_MS,
                "MAX_RECORDS" to PendingInput.MAX_RECORDS,
                "MAX_PERSISTED_BYTES" to PendingInput.MAX_PERSISTED_BYTES,
                "UNACKED_CLOSE_MS" to PendingInput.UNACKED_CLOSE_MS,
                "MAX_TOMBSTONES" to PendingInput.MAX_TOMBSTONES,
            ),
            "pending-workspace" to mapOf("UNACKED_CLOSE_MS" to PendingWorkspace.UNACKED_CLOSE_MS),
            "spawn-marker" to mapOf(
                "SPAWN_MARKER_PREFIX" to SpawnMarker.SPAWN_MARKER_PREFIX,
                "CODEX_ORIGINATOR_ENV" to SpawnMarker.CODEX_ORIGINATOR_ENV,
                "SPAWN_MARKER_MAX_CHARS" to SpawnMarker.SPAWN_MARKER_MAX_CHARS,
            ),
            "spinner-words" to mapOf("SPINNER_WORDS" to SpinnerWords.SPINNER_WORDS),
        )
    }

    @Test
    fun constantsMatchTheWeb() {
        val recorded = HelperTable.load(table).constants
        val fixtureKeys = FIXTURE_KEYS[table].orEmpty()
        val kotlin = KOTLIN[table] ?: error("no Kotlin constants registered for table $table")
        assertEquals("$table: constant names", recorded.keys - fixtureKeys, kotlin.keys)
        val mismatches = kotlin.mapNotNull { (name, value) ->
            val diff = TreeDiff.diff(recorded[name], CanonicalJson.encodeTagged(value), limit = 10)
            if (diff.isEmpty()) null else "$name:\n" + diff.joinToString("\n") { it.render() }
        }
        assertTrue("$table constants differ from the web:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
