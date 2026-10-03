package com.tether.app.client

import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.helpers.DraftForm
import com.tether.app.protocol.tree.JsBool
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-23f (T8.1 slice 5): the isolation request, its readiness, the inspect reply and its notes
 * (ta-coik.11: no setup gate; the web shows a note only).
 *
 * The web oracle for the `worktree` block is [DraftForm.buildWorktreeCreateRequest], the T2.2 port
 * of lib/draft-form.ts proven against the JS conformance corpus; the app's [WorktreeDraft.request]
 * equals it key for key on every row (ta-coik.4: the web's `Number.parseInt` rule, no app limit).
 */
class WorktreeIsolationTest {

    private val claude = ProviderCatalogEntry("claude", "claude", "ready", listOf(SessionModelOption("m1", "Model 1")))

    private fun form(useWorktree: Boolean, mode: String, base: String, branch: String, slug: String, pr: String): JsObj =
        DraftForm.INITIAL_DRAFT_FORM.with(
            "cwd" to JsStr("/w"),
            "useWorktree" to JsBool.of(useWorktree),
            "worktreeMode" to JsStr(mode),
            "worktreeBaseRef" to JsStr(base),
            "worktreeBranch" to JsStr(branch),
            "worktreeSlug" to JsStr(slug),
            "worktreePr" to JsStr(pr),
        )

    /**
     * The web's `Number.parseInt(x.trim(), 10)`, hand-evaluated, then `Number.isInteger(n) && n > 0`.
     * [retired] is what the retired ta-23f rule took (null: refused), the negative control.
     */
    private data class Pr(val raw: String, val jsParseInt: Double?, val retired: Int?)

    private val prRows = listOf(
        Pr("", null, null),
        Pr("42", 42.0, 42),
        Pr(" 42 ", 42.0, 42),
        Pr("\u00A042\u2003", 42.0, 42), // the web's trim takes NBSP and EM SPACE as well
        Pr("0042", 42.0, 42),
        Pr("1", 1.0, 1),
        Pr("9999999", 9_999_999.0, 9_999_999),
        Pr("0", 0.0, null),
        Pr("000", 0.0, null),
        Pr("-3", -3.0, null),
        Pr("abc", null, null),
        // The retired app rule refused these; the web takes a leading number and ignores the rest, and
        // sends any size (the server validates).
        Pr("42abc", 42.0, null),
        Pr("+5", 5.0, null),
        Pr("1e3", 1.0, null),
        Pr("4 2", 4.0, null),
        Pr("12.5", 12.0, null),
        Pr("0x1A", 0.0, null),
        Pr("10000000", 10_000_000.0, null),
        Pr("123456789012", 123_456_789_012.0, null),
        Pr("٤٢", null, null), // Arabic-Indic digits: NaN on the web, not ASCII digits here
    )

    @Test
    fun prNumbersAreParsedExactlyLikeTheWeb() {
        var widened = 0
        for (row in prRows) {
            // The oracle agrees with the hand-evaluated JS value (so the table itself is the web's).
            val web = DraftForm.buildWorktreeCreateRequest(form(true, "checkout-pr", "", "", "", row.raw))
            val webValid = row.jsParseInt != null && row.jsParseInt > 0 && row.jsParseInt == Math.floor(row.jsParseInt)
            val expected = if (webValid) row.jsParseInt else null
            assertEquals("web oracle for '${row.raw}'", expected, (web?.get("prNumber") as? JsNum)?.value)
            val app = WorktreeDraft.request(form(true, "checkout-pr", "", "", "", row.raw))
            assertEquals("app for '${row.raw}'", expected, (app?.get("prNumber") as? JsNum)?.value)
            // Negative control: rows the retired rule refused are now taken.
            if (webValid && row.retired == null) widened++
        }
        assertEquals("42abc, +5, 1e3, 4 2, 12.5, 10000000, 123456789012", 7, widened)
    }

    @Test
    fun anIncompletePrAsksForTheNumberWithTheWebsWords() {
        fun reason(pr: String) = WorktreeDraft.readiness(form(true, "checkout-pr", "", "", "", pr))
        for (taken in listOf("10000000", "99999999999999999999", "123456789012", " 000010000000 ", "42abc", "9999999")) assertEquals(taken, "", reason(taken))
        assertEquals(READINESS_NEED_PR, reason(""))
        assertEquals(READINESS_NEED_PR, reason("0"))
        assertEquals(READINESS_NEED_PR, reason("-3"))
        assertEquals(READINESS_NEED_PR, reason("abc"))
        assertEquals(READINESS_NEED_BRANCH, WorktreeDraft.readiness(form(true, "checkout-branch", "", " ", "", "")))
        assertEquals("", WorktreeDraft.readiness(form(true, "branch-off", "", "", "", "")))
        assertEquals("", WorktreeDraft.readiness(form(false, "checkout-pr", "", "", "", "")))
    }

    @Test
    fun thePrInputKeepsItsAsciiDigitsWithNoCap() {
        assertEquals("42", WorktreeDraft.prInput("4a2 "))
        assertEquals("12345678901234567890", WorktreeDraft.prInput("12345678901234567890"))
        assertEquals("", WorktreeDraft.prInput("٤٢"))
    }

    /**
     * The frame table: every mode × isolation × field value, the app's block against the web's, and
     * the whole create through [NewSessionGuard.resolve] (an isolated create with no block is never sent).
     */
    @Test
    fun everyModeAndFieldCombinationBuildsTheWebsBlockOrNothing() {
        val modes = listOf("branch-off", "checkout-branch", "checkout-pr")
        val bases = listOf("", "  ", " origin/dev ", "main")
        val branches = listOf("", " ", "feat/x", " pr-branch ")
        val slugs = listOf("", "x", " y ")
        var rows = 0
        for (useWorktree in listOf(true, false)) for (mode in modes) for (base in bases) for (branch in branches) for (slug in slugs) for (pr in prRows) {
            val f = form(useWorktree, mode, base, branch, slug, pr.raw)
            val web = DraftForm.buildWorktreeCreateRequest(f)
            val app = WorktreeDraft.request(f)
            rows++
            assertEquals("row $useWorktree/$mode/'$base'/'$branch'/'$slug'/'${pr.raw}'", web, app)
            // Blank fields are omitted; a sent value is trimmed.
            app?.let { block ->
                for (key in listOf("baseRef", "branch", "slug")) {
                    (block[key] as? JsStr)?.value?.let { v -> assertTrue("$key '$v' is trimmed and not blank", v.isNotBlank() && v == v.trim()) }
                }
            }
            val frame = CreateFrame.build(f, claude, DraftForm.INITIAL_USER_MODIFIED, "r")
            assertEquals(useWorktree, frame.useWorktree)
            assertEquals(app?.get("mode")?.let { (it as JsStr).value }, frame.worktree?.mode)
            assertEquals((app?.get("prNumber") as? JsNum)?.value?.toLong(), frame.worktree?.prNumber)
            val request = NewSessionRequest(NewSessionChoice("claude", "claude", null), f, DraftForm.INITIAL_USER_MODIFIED, "r", 1)
            val resolved = NewSessionGuard.resolve(request, listOf(claude), emptyList())
            if (useWorktree && app == null) assertNull("an incomplete isolated create is never built", resolved) else assertNotNull(resolved)
        }
        assertEquals(2 * 3 * 4 * 4 * 3 * prRows.size, rows)
    }

    @Test
    fun theFrameCarriesTheBlockKeyForKey() {
        val f = form(true, "checkout-pr", "ignored", " pr-42 ", " review ", " 42 ")
        val json = CreateFrame.build(f, claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"checkout-pr","slug":"review","prNumber":42,"branch":"pr-42"}"""), json["worktree"])
        // ta-coik.4: a number past the retired 9,999,999 limit and a ref past 256 characters ride as typed.
        val big = CreateFrame.build(form(true, "checkout-pr", "", "", "", "123456789012"), claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"checkout-pr","prNumber":123456789012}"""), big["worktree"])
        val long = "r".repeat(300)
        val ref = CreateFrame.build(form(true, "branch-off", long, long, "", ""), claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"branch-off","branch":"$long","baseRef":"$long"}"""), ref["worktree"])
        val off = CreateFrame.build(form(true, "branch-off", "", "", "", ""), claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"branch-off"}"""), off["worktree"])
    }

    // --- the inspect reply --------------------------------------------------------------------------

    @Test
    fun theSourceIsParsedTolerantlyAndBounded() {
        val info = buildJsonObject {
            put("cwd", "/w")
            put("isRepo", true)
            put("repoRoot", "/w")
            put("remote", "origin")
            put("defaultBaseRef", "origin/main")
            put("branches", buildJsonArray { repeat(250) { add(kotlinx.serialization.json.JsonPrimitive("b$it")) }; add(kotlinx.serialization.json.JsonPrimitive("x".repeat(300))); add(kotlinx.serialization.json.JsonPrimitive(7)) })
            put("configPresent", true)
            put("configWarnings", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("bad \u202Escript\u202C name")); repeat(40) { add(kotlinx.serialization.json.JsonPrimitive("w$it")) } })
            put("hasSetup", true)
            put("declaredScripts", buildJsonArray {
                add(buildJsonObject { put("name", "test"); put("type", "script"); put("port", kotlinx.serialization.json.JsonNull) })
                add(buildJsonObject { put("name", "web"); put("type", "service"); put("port", 3000) })
                add(buildJsonObject { put("type", "script") })
            })
        }
        val s = WorktreeSourceInfo.parse(info)
        assertTrue(s.isRepo)
        assertEquals(WorktreeSourceInfo.MAX_BRANCHES, s.branches.size)
        assertEquals("b0", s.branches.first())
        assertEquals(WorktreeSourceInfo.MAX_WARNINGS, s.configWarnings.size)
        assertFalse("bidi controls are cleaned from a warning", s.configWarnings.first().contains('\u202E'))
        assertEquals(listOf(WorktreeDeclaredScript("test", "script", null), WorktreeDeclaredScript("web", "service", 3000)), s.declaredScripts)
        assertTrue(s.hasSetup)

        val garbage = Json.parseToJsonElement("""{"isRepo":"true","hasSetup":"yes","branches":"main","defaultBaseRef":5,"configWarnings":{}}""").jsonObject
        val g = WorktreeSourceInfo.parse(garbage)
        assertFalse(g.isRepo)
        assertFalse("only a JSON true declares setup", g.hasSetup)
        assertEquals(emptyList<String>(), g.branches)
        assertEquals("", g.defaultBaseRef)
        assertEquals(WorktreeSourceInfo(), WorktreeSourceInfo.parse(JsonObject(emptyMap())))
    }

    @Test
    fun theSetupNoteIsTheWebsSentence() {
        fun note(present: Boolean, setup: Boolean, n: Int) = WorktreeCopy.setupNote(
            WorktreeSourceInfo(isRepo = true, configPresent = present, hasSetup = setup, declaredScripts = List(n) { WorktreeDeclaredScript("s$it", "script", null) }),
        )
        assertNull(note(false, true, 2))
        assertNull(note(true, false, 0))
        assertEquals("Runs this project's setup before the first turn.", note(true, true, 0))
        assertEquals("Runs this project's setup before the first turn; 1 script you can run in the session.", note(true, true, 1))
        assertEquals("Runs this project's setup before the first turn; 3 scripts you can run in the session.", note(true, true, 3))
        assertEquals("This project declares 1 script you can run in the session.", note(true, false, 1))
        assertEquals("This project declares 2 scripts you can run in the session.", note(true, false, 2))
        assertEquals("/srv/app is not a Git repository, so it cannot host an isolated session.", WorktreeCopy.notARepo("/srv/app"))
        assertEquals("That folder is not a Git repository, so it cannot host an isolated session.", WorktreeCopy.notARepo(""))
    }

    /**
     * ta-coik.11: the note is drawn exactly when the web draws it. The oracle is draft-composer.tsx
     * 90fbb9f:789-795 transcribed: `source?.configPresent && (source.hasSetup ||
     * source.declaredScripts.length > 0)`, then the sentence assembled as the JSX does.
     */
    @Test
    fun theSetupNoteShowsExactlyWhenTheWebShowsIt() {
        fun web(s: WorktreeSourceInfo): String? {
            if (!(s.configPresent && (s.hasSetup || s.declaredScripts.isNotEmpty()))) return null
            val n = s.declaredScripts.size
            return (if (s.hasSetup) "Runs this project's setup before the first turn" else "This project declares") +
                (if (n > 0) "${if (s.hasSetup) "; " else " "}$n script${if (n == 1) "" else "s"} you can run in the session" else "") + "."
        }
        var shown = 0
        var rows = 0
        for (repo in listOf(true, false)) for (present in listOf(true, false)) for (setup in listOf(true, false)) for (n in 0..3)
            for (remote in listOf("origin", "upstream", null)) for (def in listOf("origin/main", "HEAD", "")) {
                val s = WorktreeSourceInfo(
                    cwd = "/w", isRepo = repo, remote = remote, defaultBaseRef = def, configPresent = present, hasSetup = setup,
                    declaredScripts = List(n) { WorktreeDeclaredScript("s$it", "script", null) },
                )
                val expected = web(s)
                assertEquals("$s", expected, WorktreeCopy.setupNote(s))
                rows++
                if (expected != null) shown++
            }
        assertEquals(2 * 2 * 2 * 4 * 3 * 3, rows)
        // Both outcomes are reached (the oracle is not vacuous).
        assertEquals(2 * 7 * 3 * 3, shown)
    }

    @Test
    fun theSelectListsTheWebsRowsInOrder() {
        assertEquals(listOf("local", "branch-off", "checkout-branch", "checkout-pr"), WorktreeModes.OPTIONS.map { it.value })
        assertEquals(listOf("Local", "New branch", "Existing branch", "Pull request"), WorktreeModes.OPTIONS.map { it.label })
        assertEquals("local", WorktreeModes.selected(form(false, "checkout-pr", "", "", "", "")))
        assertEquals("checkout-pr", WorktreeModes.selected(form(true, "checkout-pr", "", "", "", "")))
        assertEquals("branch-off", WorktreeModes.selected(form(true, "bogus", "", "", "", "")))
    }
}
