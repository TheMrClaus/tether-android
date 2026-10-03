package com.tether.app.ui.scheduled

import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledActionsParse
import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * T9.3: the editor builds the frames the web's editor built. parity-corpus/wire/scheduled-actions.jsonl
 * is the fake-engine capture of the web console creating, editing, pausing and deleting a schedule;
 * the same steps through [ScheduleEditor] + [ScheduledRules.submit] encode byte for byte (key order
 * and explicit nulls included).
 */
class ScheduledWireTest {
    private val wire = File(System.getProperty("tether.parityCorpusWire") ?: "../../parity-corpus/wire")
    private val capture: List<JsonObject> =
        File(wire, "scheduled-actions.jsonl").readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }

    private fun frames(dir: String) = capture.filter { it["dir"]!!.jsonPrimitive.content == dir }.map { it["frame"]!!.jsonObject }

    private val utc = ZoneId.of("UTC")
    private val now = ScheduledFixtures.NOW

    /** The capture's server lists Claude with no default model (the fake engine's catalog). */
    private val claude = ProviderCatalogEntry(key = "claude", provider = "claude", status = "ready", models = emptyList())

    private fun submitted(editor: ScheduleEditor) = (ScheduledRules.submit(editor.form, editor.mode, editor.onceValue, now, utc) as SubmitResult.Ok).input

    @Test fun aNewScheduleIsTheCapturedCreate() {
        var editor = ScheduleEditor.create(claude, "<WORKSPACE_ROOT>", now, utc)
        editor = editor.copy(form = editor.form.copy(name = "Parity weekly", prompt = "Summarize the week"))
        val (form, mode, once) = ScheduledRules.selectCadence(editor.form, editor.onceValue, "custom", now, utc)
        editor = editor.copy(form = form.copy(cron = "0 9 * * 1"), mode = mode, onceValue = once)
        assertEquals(frames("c2s")[1].toString(), ClientMessage.ScheduleCreate(submitted(editor)).encode())
    }

    @Test fun anEditIsTheCapturedUpdate() {
        val schedule = ScheduledActionsParse.state(ServerMessage.parse(frames("s2c")[1]) as ServerMessage.ScheduledActions).schedules.single()
        var editor = ScheduleEditor.edit(schedule, now, utc)
        assertEquals(CadenceMode.Custom, editor.mode)
        editor = editor.copy(form = editor.form.copy(name = "Parity weekly (edited)", maxRuns = 2))
        assertEquals(frames("c2s")[2].toString(), ClientMessage.ScheduleUpdate(schedule.id, submitted(editor)).encode())
    }

    @Test fun pauseAndDeleteAreTheCapturedControls() {
        assertEquals(frames("c2s")[3].toString(), ClientMessage.ScheduleControl("sched-0001", "pause").encode())
        assertEquals(frames("c2s")[4].toString(), ClientMessage.ScheduleControl("sched-0001", "delete").encode())
    }
}
