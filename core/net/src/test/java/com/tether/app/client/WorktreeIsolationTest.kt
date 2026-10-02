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
 * ta-23f (T8.1 slice 5): the isolation request, its readiness, the setup gate and the inspect reply.
 *
 * The web oracle for the `worktree` block is [DraftForm.buildWorktreeCreateRequest], the T2.2 port
 * of lib/draft-form.ts proven against the JS conformance corpus; the app's [WorktreeDraft.request]
 * must equal it key for key on every row except the one decided divergence: a pull request number
 * that is not a plain positive integer of at most 9,999,999 (coordinator 2026-10-02).
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

    /** The web's `Number.parseInt(x.trim(), 10)`, hand-evaluated, then `Number.isInteger(n) && n > 0`. */
    private data class Pr(val raw: String, val jsParseInt: Double?, val app: Int?)

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
        // Divergences: the web takes a leading number and ignores the rest; the server refuses past 9,999,999.
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
    fun prNumbersAreParsedLikeTheWebOnlyWhenPlainAndWithinTheServersLimit() {
        for (row in prRows) {
            // The oracle agrees with the hand-evaluated JS value (so the table itself is the web's).
            val web = DraftForm.buildWorktreeCreateRequest(form(true, "checkout-pr", "", "", "", row.raw))
            val webValid = row.jsParseInt != null && row.jsParseInt > 0 && row.jsParseInt == Math.floor(row.jsParseInt)
            assertEquals("web oracle for '${row.raw}'", if (webValid) row.jsParseInt else null, (web?.get("prNumber") as? JsNum)?.value)
            assertEquals("app for '${row.raw}'", row.app, WorktreeDraft.prNumber(row.raw))
            // Where both take it, they take the same number.
            if (row.app != null) assertEquals(row.jsParseInt!!, row.app.toDouble(), 0.0)
        }
    }

    @Test
    fun aNumberPastTheLimitSaysSoAndOtherBadNumbersAskForTheNumber() {
        fun reason(pr: String) = WorktreeDraft.readiness(form(true, "checkout-pr", "", "", "", pr))
        assertEquals(READINESS_PR_TOO_LARGE, reason("10000000"))
        assertEquals(READINESS_PR_TOO_LARGE, reason("99999999999999999999"))
        assertEquals(READINESS_PR_TOO_LARGE, reason("123456789012"))
        assertEquals(READINESS_PR_TOO_LARGE, reason(" 000010000000 "))
        assertEquals(READINESS_NEED_PR, reason(""))
        assertEquals(READINESS_NEED_PR, reason("0"))
        assertEquals(READINESS_NEED_PR, reason("42abc"))
        assertEquals("", reason("9999999"))
        assertEquals(READINESS_NEED_BRANCH, WorktreeDraft.readiness(form(true, "checkout-branch", "", " ", "", "")))
        assertEquals("", WorktreeDraft.readiness(form(true, "branch-off", "", "", "", "")))
        assertEquals("", WorktreeDraft.readiness(form(false, "checkout-pr", "", "", "", "")))
    }

    @Test
    fun theFieldInputRulesKeepDigitsAndNeverCutAName() {
        assertEquals("42", WorktreeDraft.prInput("4a2 "))
        assertEquals("1234567890123456", WorktreeDraft.prInput("12345678901234567890"))
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
        var divergent = 0
        for (useWorktree in listOf(true, false)) for (mode in modes) for (base in bases) for (branch in branches) for (slug in slugs) for (pr in prRows) {
            val f = form(useWorktree, mode, base, branch, slug, pr.raw)
            val web = DraftForm.buildWorktreeCreateRequest(f)
            val app = WorktreeDraft.request(f)
            rows++
            val prDiverges = useWorktree && mode == "checkout-pr" && web != null && pr.app == null
            if (prDiverges) {
                divergent++
                assertNull("app refuses '${pr.raw}'", app)
            } else {
                assertEquals("row $useWorktree/$mode/'$base'/'$branch'/'$slug'/'${pr.raw}'", web, app)
            }
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
        assertTrue("the divergent rows exist and are all PR rows", divergent > 0)
    }

    @Test
    fun theFrameCarriesTheBlockKeyForKey() {
        val f = form(true, "checkout-pr", "ignored", " pr-42 ", " review ", " 42 ")
        val json = CreateFrame.build(f, claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"checkout-pr","slug":"review","prNumber":42,"branch":"pr-42"}"""), json["worktree"])
        val off = CreateFrame.build(form(true, "branch-off", "", "", "", ""), claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()
        assertEquals(Json.parseToJsonElement("""{"mode":"branch-off"}"""), off["worktree"])
    }

    // --- the setup gate (option A) ------------------------------------------------------------------

    /** An answer as 887c222 shapes it: origin is the repo's remote unless [remote] says otherwise. */
    private fun source(hasSetup: Boolean, defaultBaseRef: String = "origin/main", isRepo: Boolean = true, remote: String? = "origin") =
        WorktreeSourceInfo(
            cwd = "/w",
            isRepo = isRepo,
            remote = if (isRepo) remote else null,
            remotes = listOfNotNull(if (isRepo) remote else null),
            defaultBaseRef = if (isRepo) defaultBaseRef else "",
            configPresent = hasSetup,
            hasSetup = hasSetup,
        )

    @Test
    fun localAndIncompleteRequestsNeverConfirm() {
        for (s in listOf(null, source(true), source(false))) {
            assertNull(WorktreeSetupGate.confirmationFor(form(false, "checkout-pr", "", "", "", "42"), s))
            assertNull(WorktreeSetupGate.confirmationFor(form(true, "checkout-pr", "", "", "", ""), s))
            assertNull(WorktreeSetupGate.confirmationFor(form(true, "checkout-pr", "", "", "", "10000000"), s))
            assertNull(WorktreeSetupGate.confirmationFor(form(true, "checkout-branch", "", "", "", ""), s))
        }
    }

    @Test
    fun aPullRequestAndAnExistingBranchAlwaysConfirmAsMayRun() {
        for (s in listOf(null, source(true), source(false), source(false, isRepo = false))) {
            val pr = WorktreeSetupGate.confirmationFor(form(true, "checkout-pr", "origin/x", " pr-b ", "", " 0042 "), s)!!
            assertEquals(SetupConfirmation("checkout-pr", "Pull request", "#42", "pr-b", "/w", certain = false), pr)
            assertEquals(SETUP_BODY_MAY_PR, pr.body)
            val branch = WorktreeSetupGate.confirmationFor(form(true, "checkout-branch", "", " feat/x ", "", ""), s)!!
            assertEquals(SetupConfirmation("checkout-branch", "Branch", "feat/x", null, "/w", certain = false), branch)
            assertEquals(SETUP_BODY_MAY_BRANCH, branch.body)
            assertEquals(SETUP_TITLE_MAY, branch.title)
        }
    }

    @Test
    fun aNewBranchConfirmsUnlessItIsFromTheInspectedDefaultBaseWithoutSetup() {
        fun gate(base: String, s: WorktreeSourceInfo?) = WorktreeSetupGate.confirmationFor(form(true, "branch-off", base, "", "", ""), s)
        // The default base: setup declared -> will run; none declared -> no confirmation.
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/main", null, "/w", certain = true), gate("", source(true)))
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/main", null, "/w", certain = true), gate(" origin/main ", source(true)))
        assertNull(gate("", source(false)))
        assertNull(gate("origin/main", source(false)))
        // Another base: its committed config is not the inspected one -> may run, whatever hasSetup says.
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/dev", null, "/w", certain = false), gate("origin/dev", source(false)))
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/dev", null, "/w", certain = false), gate("origin/dev", source(true)))
        // Nothing inspected (no answer for this folder on this socket): fail closed.
        assertEquals(SetupConfirmation("branch-off", "Base", null, null, "/w", certain = false), gate("", null))
        assertEquals(SetupConfirmation("branch-off", "Base", "origin/main", null, "/w", certain = false), gate("origin/main", null))
        // r2: not a repository: the answer says nothing about what a create would read -> may run.
        assertEquals(SetupConfirmation("branch-off", "Base", null, null, "/w", certain = false), gate("", source(false, isRepo = false)))
        assertEquals(SETUP_BODY_WILL, gate("", source(true))!!.body)
        assertEquals(SETUP_TITLE_WILL, gate("", source(true))!!.title)
    }

    /** The gate's whole input space: a confirmation is skipped ONLY for Local, an incomplete request, or the default base with no setup. */
    @Test
    fun noConfirmationOnlyWhereNothingCanRun() {
        val sources = listOf(
            null, source(true), source(false), source(true, ""), source(false, ""), source(false, isRepo = false),
            source(false, "HEAD"), source(true, "HEAD"), source(false, "upstream/main", remote = "upstream"),
            source(true, "upstream/main", remote = "upstream"), source(false, "HEAD", remote = null), source(false, "origin/main", remote = null),
        )
        for (useWorktree in listOf(true, false)) for (mode in listOf("branch-off", "checkout-branch", "checkout-pr")) for (base in listOf("", "origin/main", "origin/dev")) for (branch in listOf("", "b")) for (pr in listOf("", "7", "10000000")) for (s in sources) {
            val f = form(useWorktree, mode, base, branch, "", pr)
            val c = WorktreeSetupGate.confirmationFor(f, s)
            val incomplete = WorktreeDraft.request(f) == null
            // r2: the default base is predicted only for a repo on origin with an origin/… default.
            val predicted = s != null && s.isRepo && s.remote == "origin" && s.defaultBaseRef.startsWith("origin/")
            val fromDefault = base.isEmpty() || base == s?.defaultBaseRef?.takeIf { it.isNotEmpty() }
            val defaultBaseNoSetup = mode == "branch-off" && predicted && !s.hasSetup && fromDefault
            val expectNone = !useWorktree || incomplete || defaultBaseNoSetup
            assertEquals("$useWorktree/$mode/'$base'/'$branch'/'$pr'/$s", expectNone, c == null)
            if (c != null) assertEquals(mode == "branch-off" && predicted && s.hasSetup && fromDefault, c.certain)
        }
    }

    // --- r2: the default base the answer can vouch for ---------------------------------------------

    private enum class Gate { NONE, MAY, WILL }

    private fun outcome(c: SetupConfirmation?) = when { c == null -> Gate.NONE; c.certain -> Gate.WILL; else -> Gate.MAY }

    /** One row: what a New branch with [base] typed shows for [s], and the ref the dialog names. */
    private data class Row(val name: String, val s: WorktreeSourceInfo?, val base: String, val gate: Gate, val ref: String?)

    /**
     * The coordinator's rule, row by row: a New branch from the default base skips the confirmation
     * ONLY for a repo on remote origin whose default is an origin/… ref and whose hasSetup is a JSON
     * false; everything else confirms as may-run and, unless the base was typed, names no ref.
     */
    @Test
    fun theDefaultBaseIsTrustedOnlyOnOriginWithAnOriginDefault() {
        val rows = listOf(
            // positive controls
            Row("origin, origin/main, no setup", source(false), "", Gate.NONE, null),
            Row("origin, origin/main typed, no setup", source(false), "origin/main", Gate.NONE, null),
            Row("origin, origin/main, setup", source(true), "", Gate.WILL, "origin/main"),
            // a non-origin remote: the create resolves origin (or HEAD), not upstream/main
            Row("upstream only, no setup", source(false, "upstream/main", remote = "upstream"), "", Gate.MAY, null),
            Row("upstream only, setup", source(true, "upstream/main", remote = "upstream"), "", Gate.MAY, null),
            Row("upstream only, its default typed", source(false, "upstream/main", remote = "upstream"), "upstream/main", Gate.MAY, "upstream/main"),
            // no remote at all: the default is HEAD
            Row("no remote, HEAD, no setup", source(false, "HEAD", remote = null), "", Gate.MAY, null),
            Row("no remote, HEAD, setup", source(true, "HEAD", remote = null), "", Gate.MAY, null),
            Row("no remote, an origin/main default (inconsistent)", source(false, "origin/main", remote = null), "", Gate.MAY, null),
            // origin without a recorded default: HEAD moves with a local branch switch
            Row("origin, HEAD, no setup", source(false, "HEAD"), "", Gate.MAY, null),
            Row("origin, HEAD, setup", source(true, "HEAD"), "", Gate.MAY, null),
            Row("origin, HEAD typed", source(false, "HEAD"), "HEAD", Gate.MAY, "HEAD"),
            Row("origin, a default on another remote (inconsistent)", source(false, "upstream/main"), "", Gate.MAY, null),
            Row("origin, an empty default", source(false, ""), "", Gate.MAY, null),
            // not a repository, or an answer that cannot be taken at its word
            Row("not a repo", source(false, isRepo = false), "", Gate.MAY, null),
            Row("hasSetup not a boolean", source(false).copy(setupKnown = false), "", Gate.MAY, null),
            Row("config read reported failed", source(false).copy(configKnown = false), "", Gate.MAY, null),
            Row("nothing inspected", null, "", Gate.MAY, null),
        )
        for (r in rows) {
            val c = WorktreeSetupGate.confirmationFor(form(true, "branch-off", r.base, "", "", ""), r.s)
            assertEquals(r.name, r.gate, outcome(c))
            if (c != null) {
                assertEquals(r.name, r.ref, c.ref)
                assertEquals(r.name, "branch-off", c.mode)
                if (c.certain) assertEquals(r.name, SETUP_BODY_WILL, c.body)
                else assertEquals(r.name, if (r.ref == null) SETUP_BODY_MAY_DEFAULT else SETUP_BODY_MAY_BRANCH, c.body)
            }
            assertEquals(r.name, r.gate != Gate.MAY, WorktreeSetupGate.predictsDefaultBase(r.s))
        }
    }

    /** The verifier's scenario, as the 887c222 server words the reply (worktreeSourceInfo, server.mjs ~6105). */
    @Test
    fun theVerifiersUpstreamOnlyReplyConfirms() {
        fun reply(remote: String, def: String) = WorktreeSourceInfo.parse(Json.parseToJsonElement("""
            {"cwd":"/w","isRepo":true,"repoRoot":"/w","remote":"$remote","remotes":["$remote"],"currentBranch":"main",
             "defaultBaseRef":"$def","branches":["main","$def"],"configPresent":false,"configWarnings":[],
             "hasSetup":false,"hasTeardown":false,"declaredScripts":[]}
        """).jsonObject)
        val upstream = reply("upstream", "upstream/main")
        assertTrue(upstream.setupKnown && upstream.configKnown)
        val c = WorktreeSetupGate.confirmationFor(form(true, "branch-off", "", "", "", ""), upstream)
        assertEquals(SetupConfirmation("branch-off", "Base", null, null, "/w", certain = false), c)
        assertEquals(SETUP_TITLE_MAY, c!!.title)
        assertEquals(SETUP_BODY_MAY_DEFAULT, c.body)
        // The control the verifier ran: the same repo on origin creates from origin/main, setup none.
        assertNull(WorktreeSetupGate.confirmationFor(form(true, "branch-off", "", "", "", ""), reply("origin", "origin/main")))
        // And the frame stays the web's: no remote is added.
        assertEquals(Json.parseToJsonElement("""{"mode":"branch-off"}"""),
            CreateFrame.build(form(true, "branch-off", "", "", "", ""), claude, DraftForm.INITIAL_USER_MODIFIED, "r").toJsonObject()["worktree"])
    }

    /**
     * 887c222 readProjectConfig answers `present: false, warnings: []` both for "no tether.json" and
     * for a `git show` that failed, so the wire cannot tell them apart: that reply stays unconfirmed.
     * A reply that reports a failed read (not present, yet warnings) or a non-boolean configPresent
     * is not taken at its word. A file that is present but unusable (warnings, no setup) is what the
     * create reads too, so it needs no confirmation.
     */
    @Test
    fun aConfigReadReportedAsFailedIsUnknown() {
        fun parsed(present: String, warnings: String, hasSetup: String = "false") = WorktreeSourceInfo.parse(Json.parseToJsonElement(
            """{"cwd":"/w","isRepo":true,"remote":"origin","defaultBaseRef":"origin/main","configPresent":$present,"configWarnings":$warnings,"hasSetup":$hasSetup}""",
        ).jsonObject)
        val f = form(true, "branch-off", "", "", "", "")
        val noFile = parsed("false", "[]")
        assertTrue(noFile.configKnown)
        assertNull("the legitimate no-config reply: no confirmation", WorktreeSetupGate.confirmationFor(f, noFile))
        assertNull("no warnings key at all: the same", WorktreeSetupGate.confirmationFor(f, WorktreeSourceInfo.parse(Json.parseToJsonElement(
            """{"cwd":"/w","isRepo":true,"remote":"origin","defaultBaseRef":"origin/main","configPresent":false,"hasSetup":false}""").jsonObject)))
        val invalid = parsed("true", """["tether.json is not valid JSON and was ignored."]""")
        assertTrue(invalid.configKnown)
        assertNull("a present but unusable file declares nothing the create would run", WorktreeSetupGate.confirmationFor(f, invalid))
        for ((present, warnings) in listOf("false" to """["could not read tether.json"]""", "false" to """[""]""", "\"false\"" to "[]", "null" to "[]", "0" to "[]")) {
            val s = parsed(present, warnings)
            assertFalse("$present/$warnings", s.configKnown)
            assertEquals("$present/$warnings", Gate.MAY, outcome(WorktreeSetupGate.confirmationFor(f, s)))
        }
        assertFalse(WorktreeSourceInfo.parse(Json.parseToJsonElement("""{"isRepo":true}""").jsonObject).configKnown)
    }

    /** A mode the app does not offer (the builder copies the form's) confirms as may-run, never drawn raw. */
    @Test
    fun anUnknownModeConfirmsAsMayRun() {
        for (mode in listOf("squash", "", "LOCAL", "branch_off", "\u202Ebranch-off")) for (s in listOf(null, source(false), source(true))) {
            val f = form(true, mode, "", "", "", "")
            assertNotNull("the web's builder builds a block for '$mode'", DraftForm.buildWorktreeCreateRequest(f))
            val c = WorktreeSetupGate.confirmationFor(f, s)
            assertNotNull("'$mode'/$s", c)
            assertFalse(c!!.certain)
            assertEquals(SETUP_MODE_UNKNOWN, c.modeLabel)
            assertEquals(SETUP_TITLE_MAY, c.title)
        }
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
        assertFalse("a reply that does not say whether setup runs is not known", g.setupKnown)
        assertTrue(s.setupKnown)
        assertEquals(WorktreeSourceInfo(setupKnown = false, configKnown = false), WorktreeSourceInfo.parse(JsonObject(emptyMap())))
        // The gate fails closed on it: a new branch from the default base confirms (may run).
        val f = form(true, "branch-off", "", "", "", "")
        assertEquals(false, WorktreeSetupGate.confirmationFor(f, g.copy(isRepo = true, remote = "origin", defaultBaseRef = "origin/main", configKnown = true))?.certain)
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

    @Test
    fun theSelectListsTheWebsRowsInOrder() {
        assertEquals(listOf("local", "branch-off", "checkout-branch", "checkout-pr"), WorktreeModes.OPTIONS.map { it.value })
        assertEquals(listOf("Local", "New branch", "Existing branch", "Pull request"), WorktreeModes.OPTIONS.map { it.label })
        assertEquals("local", WorktreeModes.selected(form(false, "checkout-pr", "", "", "", "")))
        assertEquals("checkout-pr", WorktreeModes.selected(form(true, "checkout-pr", "", "", "", "")))
        assertEquals("branch-off", WorktreeModes.selected(form(true, "bogus", "", "", "", "")))
    }
}
