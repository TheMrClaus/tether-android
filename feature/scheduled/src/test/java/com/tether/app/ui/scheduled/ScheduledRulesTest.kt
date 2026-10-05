package com.tether.app.ui.scheduled

import com.tether.app.client.ProviderCatalogEntry
import com.tether.app.client.ScheduledAction
import com.tether.app.client.ScheduledActionRun
import com.tether.app.protocol.ModelVariantOption
import com.tether.app.protocol.ScheduledActionInput
import com.tether.app.protocol.SessionModelOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** Fixed inputs every scheduled test shares (UTC, en-US, 2026-10-03 12:00 UTC). */
object ScheduledFixtures {
    val UTC: ZoneId = ZoneId.of("UTC")
    val US: Locale = Locale.US
    const val NOW = 1_791_028_800_000L // 2026-10-03T12:00:00Z

    fun schedule(
        id: String = "sched-1",
        name: String = "Morning issue triage",
        prompt: String = "Review new issues and pull requests, then summarize what needs attention.",
        provider: String = "claude",
        model: String? = null,
        cron: String = "0 9 * * 1-5",
        timeZone: String = "UTC",
        maxRuns: Int? = null,
        status: String = "active",
        nextRunAt: Long? = NOW + 3 * 3_600_000,
        runs: List<ScheduledActionRun> = emptyList(),
        unknown: JsonObject = JsonObject(emptyMap()),
        cwd: String = "/home/op/projects/tether",
    ) = ScheduledAction(
        id = id, name = name, prompt = prompt, cwd = cwd, provider = provider, profileId = null, model = model,
        reasoningEffort = null, permissionMode = null, sandboxPolicy = null, useWorktree = false, cron = cron,
        timeZone = timeZone, maxRuns = maxRuns, status = status, createdAt = NOW - 86_400_000, updatedAt = NOW - 86_400_000,
        nextRunAt = nextRunAt, lastRunAt = runs.lastOrNull()?.startedAt, runs = runs, unknown = unknown,
    )

    fun run(id: String, status: String, endedAt: Long?, sessionId: String? = null, error: String? = null, manual: Boolean = false) =
        ScheduledActionRun(id, status, (endedAt ?: NOW) - 60_000, endedAt, sessionId, error, manual)

    val claude = ProviderCatalogEntry(
        key = "claude", provider = "claude", status = "ready", label = "Claude", defaultModel = "claude-opus",
        models = listOf(
            SessionModelOption("claude-opus", "Claude Opus", variants = listOf(ModelVariantOption("low", "Low"), ModelVariantOption("high", "High"))),
            SessionModelOption("claude-old", "Claude Old", legacy = true),
        ),
    )
    val codexWork = ProviderCatalogEntry(key = "work", provider = "codex", status = "ready", label = "Codex (work)", profileId = "work", defaultModel = "", models = listOf(SessionModelOption("gpt-5", "GPT-5")))
    val gemini = ProviderCatalogEntry(key = "gemini", provider = "gemini", status = "ready", models = emptyList())
    val loading = ProviderCatalogEntry(key = "opencode", provider = "opencode", status = "loading", models = emptyList())
}

/** T9.3: the pure parts of components/scheduled-actions-view.tsx, case by case. */
class ScheduledRulesTest {
    private val f = ScheduledFixtures

    @Test fun aOneOffIsAPinnedCronWithOneRun() {
        assertTrue(ScheduledRules.isOneOffCron("5 9 4 10 *"))
        assertTrue(ScheduledRules.isOneOffCron("  5   9 4 10 *  "))
        assertFalse(ScheduledRules.isOneOffCron("5 9 4 10 1"))
        assertFalse(ScheduledRules.isOneOffCron("*/5 9 4 10 *"))
        assertFalse(ScheduledRules.isOneOffCron("5 9 4 10"))
        assertTrue(ScheduledRules.isOneOffSchedule("5 9 4 10 *", 1))
        assertFalse(ScheduledRules.isOneOffSchedule("5 9 4 10 *", 2))
        assertFalse(ScheduledRules.isOneOffSchedule("5 9 4 10 *", null))
    }

    @Test fun theCadenceModeOfACron() {
        assertEquals(CadenceMode.Once, ScheduledRules.cadenceModeFor("5 9 4 10 *", 1))
        assertEquals(CadenceMode.Preset, ScheduledRules.cadenceModeFor("0 9 * * 1-5", null))
        assertEquals(CadenceMode.Preset, ScheduledRules.cadenceModeFor("*/15 * * * *", 3))
        assertEquals(CadenceMode.Custom, ScheduledRules.cadenceModeFor("0 9 * * 1", null))
        assertEquals(CadenceMode.Custom, ScheduledRules.cadenceModeFor("5 9 4 10 *", null))
    }

    @Test fun theRunAtValueAndItsCron() {
        assertEquals("2026-10-04T09:00", ScheduledRules.defaultOnceInputValue(f.NOW, f.UTC))
        assertEquals("5 9 4 10 *", ScheduledRules.cronFromOnceInputValue("2026-10-04T09:05"))
        assertNull(ScheduledRules.cronFromOnceInputValue("2026-10-04 09:05"))
        assertEquals(1_791_104_700_000L, ScheduledRules.dateFromOnceInputValue("2026-10-04T09:05", f.UTC))
        assertNull(ScheduledRules.dateFromOnceInputValue("", f.UTC))
        // From the live next run, in the schedule's own zone (it carries the year).
        val live = f.schedule(cron = "30 7 1 3 *", maxRuns = 1, timeZone = "Europe/Rome", nextRunAt = 1_803_882_600_000L) // 2027-03-01T06:30Z
        assertEquals("2027-03-01T07:30", ScheduledRules.onceInputValueFromSchedule(live, f.NOW, f.UTC))
        // Paused / completed: the cron's own fields, in the current year.
        assertEquals("2026-03-01T07:30", ScheduledRules.onceInputValueFromSchedule(live.copy(nextRunAt = null), f.NOW, f.UTC))
    }

    @Test fun relativeTimesReadAsTheWebsIntl() {
        // Generated with node 22 from the web's own timeLabel (TZ=UTC, en-US, now = 2026-10-03T12:00Z).
        val expected = listOf(
            0L to "this minute", 29_999L to "this minute", 30_000L to "in 1 minute", -30_000L to "this minute",
            -30_001L to "1 minute ago", 60_000L to "in 1 minute", -60_000L to "1 minute ago", 300_000L to "in 5 minutes",
            5_399_999L to "in 90 minutes", 5_400_000L to "in 2 hours", -5_400_000L to "1 hour ago", 9_000_000L to "in 3 hours",
            -9_000_000L to "2 hours ago", 82_800_000L to "in 23 hours", 86_399_999L to "in 24 hours", 86_400_000L to "tomorrow",
            129_600_000L to "in 2 days", -129_600_000L to "yesterday", 1_157_760_000L to "in 13 days",
            1_209_600_000L to "Oct 17, 2026, 12:00 PM", -1_728_000_000L to "Sep 13, 2026, 12:00 PM",
        )
        for ((delta, label) in expected) assertEquals("delta $delta", label, ScheduledRules.timeLabel(f.NOW + delta, f.NOW, f.UTC, f.US))
        assertEquals("Not yet", ScheduledRules.timeLabel(null, f.NOW, f.UTC, f.US))
        assertEquals("Not yet", ScheduledRules.timeLabel(0, f.NOW, f.UTC, f.US))
    }

    @Test fun theRowLabels() {
        assertEquals("Weekdays at 09:00", ScheduledRules.cadenceLabel(f.schedule(), f.UTC, f.US))
        assertEquals("0 9 * * 1", ScheduledRules.cadenceLabel(f.schedule(cron = "0 9 * * 1"), f.UTC, f.US))
        assertEquals("Once · Oct 4, 2026, 9:05 AM", ScheduledRules.cadenceLabel(f.schedule(cron = "5 9 4 10 *", maxRuns = 1, nextRunAt = 1_791_104_700_000L), f.UTC, f.US))
        assertEquals("Runs once", ScheduledRules.cadenceLabel(f.schedule(cron = "5 9 4 10 *", maxRuns = 1, nextRunAt = null), f.UTC, f.US))

        assertEquals("Next run", ScheduledRules.nextCaption(f.schedule()))
        assertEquals("Paused", ScheduledRules.nextCaption(f.schedule(status = "paused")))
        assertEquals("Runs", ScheduledRules.nextCaption(f.schedule(status = "completed")))
        assertEquals("in 3 hours", ScheduledRules.nextValue(f.schedule(), f.NOW, f.UTC, f.US))
        assertEquals("No run queued", ScheduledRules.nextValue(f.schedule(status = "paused"), f.NOW, f.UTC, f.US))
        // Completed counts the scheduled runs only, never a Run now.
        val runs = listOf(f.run("a", "succeeded", f.NOW - 7_200_000), f.run("b", "succeeded", f.NOW - 3_600_000, manual = true), f.run("c", "failed", f.NOW))
        assertEquals("2 completed", ScheduledRules.nextValue(f.schedule(status = "completed", runs = runs), f.NOW, f.UTC, f.US))
    }

    @Test fun theLastRunLineReadsTheFirstKeptRunAsTheWebDoes() {
        assertEquals("Never run", ScheduledRules.runSummary(f.schedule(), f.NOW, f.UTC, f.US))
        val runs = listOf(f.run("old", "succeeded", f.NOW - 7_200_000), f.run("new", "failed", f.NOW - 60_000, error = "boom"))
        // scheduled-actions-view.tsx:174 reads runs[0], the oldest the server kept.
        assertEquals("Last ran 2 hours ago", ScheduledRules.runSummary(f.schedule(runs = runs), f.NOW, f.UTC, f.US))
        assertEquals("Last run failed · 1 minute ago", ScheduledRules.runSummary(f.schedule(runs = runs.reversed()), f.NOW, f.UTC, f.US))
        assertEquals("Running now", ScheduledRules.runSummary(f.schedule(runs = listOf(f.run("r", "running", null))), f.NOW, f.UTC, f.US))
        // The pill reads the NEWEST run.
        assertEquals("Running", ScheduledRules.statusWord(f.schedule(runs = listOf(f.run("o", "succeeded", f.NOW), f.run("r", "running", null)))))
        assertEquals("paused", ScheduledRules.statusWord(f.schedule(status = "paused")))
    }

    @Test fun theAgentsAScheduleCanRun() {
        assertEquals(listOf("claude", "work"), ScheduledRules.entries(listOf(f.claude, f.gemini, f.loading, f.codexWork)).map { it.key })
    }

    @Test fun aNewScheduleStartsFromTheFirstAgentsDefaults() {
        val form = ScheduledRules.emptyForm(f.claude, "/w", ZoneId.of("Europe/Madrid"))
        assertEquals(ScheduleForm(cwd = "/w", providerKey = "claude", provider = "claude", model = "claude-opus", cron = "0 9 * * 1-5", timeZone = "Europe/Madrid"), form)
        // No agent: Claude, no key, no model.
        assertEquals(ScheduleForm(cwd = "/w", timeZone = "UTC"), ScheduledRules.emptyForm(null, "/w", f.UTC))
        // An empty default model is "Provider default" (null).
        assertNull(ScheduledRules.emptyForm(f.codexWork, "/w", f.UTC).model)
    }

    @Test fun pickingAnAgentTakesItsDefaultsAndDropsTheEffort() {
        val entries = listOf(f.claude, f.codexWork)
        val form = ScheduledRules.emptyForm(f.claude, "/w", f.UTC).copy(reasoningEffort = "high")
        val picked = ScheduledRules.selectProvider(form, entries, "work")
        assertEquals(listOf("work", "codex", "work", null, null), listOf(picked.providerKey, picked.provider, picked.profileId, picked.model, picked.reasoningEffort))
        assertEquals(form, ScheduledRules.selectProvider(form, entries + f.gemini, "gemini"))
        assertEquals(form, ScheduledRules.selectProvider(form, entries, "nope"))
    }

    @Test fun theCadenceSelect() {
        val form = ScheduleForm(cron = "0 9 * * 1-5", timeZone = "Europe/Madrid", maxRuns = 4)
        val (custom, customMode, _) = ScheduledRules.selectCadence(form, "", "custom", f.NOW, f.UTC)
        assertEquals(form, custom)
        assertEquals(CadenceMode.Custom, customMode)
        val (once, onceMode, onceValue) = ScheduledRules.selectCadence(form, "", "once", f.NOW, f.UTC)
        assertEquals(CadenceMode.Once, onceMode)
        assertEquals("2026-10-04T09:00", onceValue)
        assertEquals(form.copy(cron = "0 9 4 10 *", maxRuns = 1, timeZone = "UTC"), once)
        val (preset, presetMode, _) = ScheduledRules.selectCadence(once, onceValue, "0 * * * *", f.NOW, f.UTC)
        assertEquals(CadenceMode.Preset, presetMode)
        // As on the web, leaving Run once keeps its run limit and zone.
        assertEquals(once.copy(cron = "0 * * * *"), preset)
    }

    private fun ok(result: SubmitResult): ScheduledActionInput = (result as SubmitResult.Ok).input

    @Test fun submitTrimsAndSendsEveryField() {
        val extra = JsonObject(mapOf("setupConsent" to JsonPrimitive("none")))
        val form = ScheduleForm(
            name = "  Nightly ", prompt = " Run the checks\n", cwd = " /workspace/project ", providerKey = "work", provider = "codex", profileId = "work",
            model = "gpt-5", reasoningEffort = "high", permissionMode = "plan", sandboxPolicy = "read-only", useWorktree = true,
            cron = " 30 2 * * * ", timeZone = " Europe/Rome ", maxRuns = 3, extra = extra,
        )
        assertEquals(
            ScheduledActionInput("Nightly", "Run the checks", "/workspace/project", "codex", "work", "gpt-5", "high", "plan", "read-only", true, "30 2 * * *", "Europe/Rome", 3, extra = extra),
            ok(ScheduledRules.submit(form, CadenceMode.Custom, "", f.NOW, f.UTC)),
        )
    }

    @Test fun submitRefusesWhatTheWebRefuses() {
        val good = ScheduleForm(name = "n", prompt = "p", cwd = "/w")
        fun error(form: ScheduleForm, mode: CadenceMode = CadenceMode.Preset, once: String = "") =
            (ScheduledRules.submit(form, mode, once, f.NOW, f.UTC) as SubmitResult.Error).message
        assertEquals("Name, prompt, and workspace are required.", error(good.copy(name = "  ")))
        assertEquals("Name, prompt, and workspace are required.", error(good.copy(prompt = "")))
        assertEquals("Name, prompt, and workspace are required.", error(good.copy(cwd = " ")))
        assertEquals("Pick a date and time to run once.", error(good, CadenceMode.Once, ""))
        assertEquals("Pick a time in the future.", error(good, CadenceMode.Once, "2026-10-03T12:00"))
        assertEquals("Cron cadence must contain five fields.", error(good.copy(cron = "0 9 * *"), CadenceMode.Custom))
        assertEquals("Cron cadence must contain five fields.", error(good.copy(cron = ""), CadenceMode.Custom))
        // The browser's own check of `<input type="number" min={1}>` comes first.
        assertEquals(ScheduledRules.RUN_LIMIT_MIN_MESSAGE, error(good.copy(name = "", maxRuns = 0)))
        // ... and never for the disabled Run once input.
        ok(ScheduledRules.submit(good.copy(maxRuns = 0, cron = "0 9 4 10 *"), CadenceMode.Once, "2026-10-04T09:00", f.NOW, f.UTC))
        ok(ScheduledRules.submit(good, CadenceMode.Once, "2026-10-03T12:01", f.NOW, f.UTC))
    }

    @Test fun anEditStartsFromTheSchedule() {
        val unknown = JsonObject(mapOf("setupConsent" to JsonPrimitive("none")))
        val s = f.schedule(provider = "codex", model = "gpt-5", maxRuns = 5, unknown = unknown).copy(profileId = "work", sandboxPolicy = "off", useWorktree = true)
        val form = ScheduledRules.formFor(s)
        assertEquals("work", form.providerKey)
        assertEquals(unknown, form.extra)
        assertEquals(s.toInput(), ok(ScheduledRules.submit(form, CadenceMode.Preset, "", f.NOW, f.UTC)))
        assertEquals("codex", ScheduledRules.formFor(s.copy(profileId = null)).providerKey)
    }

    @Test fun theWorkspaceSuggestions() {
        assertEquals(listOf("/w", "/p"), ScheduledRules.workspaceSuggestions("/w", listOf("/p", "/w", "")))
        assertEquals(listOf("/p"), ScheduledRules.workspaceSuggestions("", listOf("/p")))
    }

    @Test fun theDeepSeekPeakHint() {
        // 2026-10-12 (a Monday, after the Golden Week holiday) 02:00 UTC is in the 01:00-04:00 peak window.
        val peak = 1_791_770_400_000L
        assertTrue(ScheduledRules.deepSeekPeakHint(f.schedule(provider = "dsh", nextRunAt = peak)))
        assertFalse("only an active schedule", ScheduledRules.deepSeekPeakHint(f.schedule(provider = "dsh", nextRunAt = peak, status = "paused")))
        assertFalse("off-peak (04:00 UTC)", ScheduledRules.deepSeekPeakHint(f.schedule(provider = "dsh", nextRunAt = peak + 2 * 3_600_000)))
        assertFalse("not DeepSeek", ScheduledRules.deepSeekPeakHint(f.schedule(provider = "claude", nextRunAt = peak)))
    }

    @Test fun theEditorSurvivesItsSavedForm() {
        val editor = ScheduleEditor(
            editingId = "s1",
            form = ScheduleForm("n", "p", "/w", "work", "codex", "work", "m", "high", "plan", "off", true, "1 2 3 4 *", "Europe/Rome", 1, JsonObject(mapOf("x" to JsonPrimitive(1)))),
            mode = CadenceMode.Once,
            onceValue = "2026-04-03T02:01",
            error = "Pick a time in the future.",
        )
        assertEquals(editor, ScheduleEditor.decode(ScheduleEditor.encode(editor)))
        val fresh = ScheduleEditor.create(null, "", f.NOW, f.UTC)
        assertEquals(fresh, ScheduleEditor.decode(ScheduleEditor.encode(fresh)))
        assertNull(ScheduleEditor.decode("{not json"))
    }
}
