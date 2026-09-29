package com.tether.app.ui.chat

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T6.3: where the cards go (chat-view.tsx:3351-3662), which lock applies, and what a choice sends. */
class ApprovalModelTest {

    private fun items(fixture: ChatFixtures.Folded, open: Boolean = false, showApprovals: Boolean = true) =
        buildChatItems(fixture.projection, fixture.tree, showThinking = false, zone = ChatFixtures.zone, groupOpen = { _, d -> open || d }, showApprovals = showApprovals)

    private fun ChatItem.label(): String = when (this) {
        is ChatItem.Block -> "block:${block.blockId}"
        is ChatItem.ToolGroup -> "group:$firstBlockId"
        is ChatItem.Denial -> "denial:${denial.toolId}${if (turnId == null) ":homeless" else ""}${if (nested) ":nested" else ""}"
        is ChatItem.Answered -> "answered:${answered.requestId}"
        is ChatItem.Approval -> "approval:${approval.requestId}"
        is ChatItem.Question -> "question:${question.requestId}${if (question.requestId.isNotEmpty() && answered) ":answered" else ""}"
        else -> this::class.simpleName!!
    }

    @Test fun thePendingApprovalSitsBelowTheTranscript() {
        val labels = items(ApprovalFixtures.write).map { it.label() }
        assertEquals("approval:req-w", labels.last())
        assertTrue(items(ApprovalFixtures.write).last().startsGroup)
        assertEquals(listOf("block:t1:m0", "group:toolu_w"), labels.filter { it.startsWith("block:t1:m") || it.startsWith("group") })
    }

    @Test fun interactiveApprovalsOffHidesTheApprovalCardOnly() {
        assertTrue(items(ApprovalFixtures.write, showApprovals = false).none { it is ChatItem.Approval })
        assertTrue(items(ApprovalFixtures.question, showApprovals = false).any { it is ChatItem.Question })
    }

    @Test fun onlyTheActiveTurnsRequestsAreShownAndAResolvedOneGoes() {
        val resolved = foldTree(ApprovalFixtures.write.tree, ev("approval_resolved", "t1", ts = 1) { put("requestId", "req-w"); put("decision", "allow") })
        val gone = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(resolved)!!, resolved)
        assertTrue(items(gone).none { it is ChatItem.Approval })
        val expired = foldTree(ApprovalFixtures.write.tree, ev("approval_expired", "t1", ts = 1) { put("requestId", "req-w") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(expired)!!, expired)).none { it is ChatItem.Approval })
        // A turn that is not the active one never shows its leftovers (the web reads activeTurn only).
        val ended = foldTree(ApprovalFixtures.write.tree, ev("turn_end", "t1", ts = 1) { put("outcome", "ok") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(ended)!!, ended)).none { it is ChatItem.Approval })
    }

    @Test fun aPendingQuestionReplacesTheAskUserQuestionCardAndACancelledOneGoes() {
        val labels = items(ApprovalFixtures.question).map { it.label() }
        assertEquals("question:q-1", labels.last())
        assertFalse("the AskUserQuestion tool card is suppressed", labels.any { it.contains("ask-1") })
        val cancelled = foldTree(ApprovalFixtures.question.tree, ev("question_cancelled", "t1", ts = 1) { put("requestId", "q-1") })
        assertTrue(items(ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(cancelled)!!, cancelled)).none { it is ChatItem.Question })
    }

    @Test fun anAnswerOnRecordMarksThePendingQuestionAnswered() {
        val tree = foldTree(
            ApprovalFixtures.question.tree,
            ev("question_answered", "t1", ts = 1) { put("requestId", "q-1"); put("toolId", "ask-1") },
        )
        val folded = ChatFixtures.Folded(LegacyProjectionAdapter.adaptOnce(tree)!!, tree)
        assertEquals("question:q-1:answered", items(folded).last().label())
    }

    @Test fun theAnsweredRecordTakesTheToolSlotAndABlocklessOneTrailsItsTurn() {
        val labels = items(ApprovalFixtures.answered).map { it.label() }
        val slot = labels.indexOf("answered:q-1")
        assertEquals("block:t1:m0", labels[slot - 1])
        assertEquals("block:t1:m1", labels[slot + 1])
        assertEquals(slot + 2, labels.indexOf("answered:q-2"))
        assertTrue(labels.none { it.startsWith("question") })
    }

    @Test fun denialsFollowTheirCallInsideTheOpenGroupTrailTheirTurnOrSitBelowEverything() {
        val closed = items(ApprovalFixtures.denials).map { it.label() }
        // A closed group hides its cards and the denials anchored to them (the web's <details>).
        assertFalse(closed.any { it == "denial:toolu_b:nested" || it == "denial:child-read:nested" })
        assertTrue(closed.indexOf("denial:toolu_gone") > closed.indexOf("block:t1:m1"))
        assertEquals("denial:toolu_late:homeless", closed.last())

        val open = items(ApprovalFixtures.denials, open = true).map { it.label() }
        assertEquals(open.indexOf("block:toolu_b") + 1, open.indexOf("denial:toolu_b:nested"))
        // A sub-agent's refused call anchors to its PARENT Agent block.
        assertEquals(open.indexOf("block:task-1") + 1, open.indexOf("denial:child-read:nested"))
        val denial = items(ApprovalFixtures.denials, open = true).filterIsInstance<ChatItem.Denial>()
        val child = denial.first { it.denial.toolId == "child-read" }
        assertEquals(RunRef("t1::task-1", "Check fixtures"), child.run)
        assertEquals(DenialTarget("File", "/srv/fixtures/secret.env"), child.target)
        assertEquals(DenialTarget("Command", "rm -rf build/ && git clean -fdx"), denial.first { it.denial.toolId == "toolu_b" }.target)
        assertNull(denial.first { it.denial.toolId == "toolu_b" }.run)
        assertEquals(denial.map { it.key }.distinct(), denial.map { it.key })
    }

    @Test fun theLockNamesWhyInOrder() {
        val s = AgentSession(id = "s1", provider = "claude", name = "n", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
        assertNull(consentLock(connected = true, live = true, session = s))
        assertEquals(ConsentLock.CatchingUp, consentLock(connected = true, live = false, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, live = true, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, live = false, session = s))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = true, live = true, session = s.copy(readOnly = true)))
        assertEquals(ConsentLock.HandedOff, consentLock(connected = true, live = true, session = s.copy(handedOffTo = "s2")))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = false, live = false, session = s.copy(readOnly = true, handedOffTo = "s2")))
    }

    @Test fun aChoiceSendsOnlyWhatItsGrantAllows() {
        val view = pendingApprovals(ApprovalFixtures.grants.tree).single()
        val (all, some, deny) = view.choices
        val requested = checkNotNull(view.requested)
        assertEquals(listOf("/srv/fixtures", "/srv/schema.sql"), requested.read)
        assertEquals(listOf("/w/report"), requested.write)
        assertTrue(requested.network)
        val full = subsetGrant(linkedSetOf("/srv/schema.sql", "/srv/fixtures"), setOf("/w/report"), true)!!
        val subset = subsetGrant(setOf("/srv/schema.sql"), emptySet(), true)
        assertEquals(GrantedPermissions(fileSystemRead = listOf("/srv/schema.sql"), fileSystemWrite = null, networkEnabled = true), subset)
        // Round 4: EVERY grant needs the confirmation, full or partial.
        assertNull(pickFor(view, all, confirmed = false, subset = full))
        assertNull(pickFor(view, some, confirmed = false, subset = full))
        assertNull(pickFor(view, some, confirmed = false, subset = subset))
        assertEquals(ApprovalPick("some", subset), pickFor(view, some, confirmed = true, subset = subset))
        assertEquals(ApprovalPick("some", full), pickFor(view, some, confirmed = true, subset = full))
        // "Allow all" grants what was confirmed only when everything is ticked.
        assertEquals(ApprovalPick("all", requested.exact), pickFor(view, all, confirmed = true, subset = full))
        assertNull(pickFor(view, all, confirmed = true, subset = subset))
        assertNull("a subset needs something ticked", pickFor(view, some, confirmed = true, subset = subsetGrant(emptySet(), emptySet(), false)))
        assertEquals(ApprovalPick("deny", null), pickFor(view, deny, confirmed = false, subset = subset))
        assertEquals(
            "Confirm these permissions: read ${iso("“/srv/schema.sql”")}; network access.",
            grantSummary(listOf("/srv/schema.sql"), emptyList(), true),
        )
        assertTrue(view.needsConfirm)
    }

    @Test fun approvalViewReadsTheWebsFields() {
        val view = pendingApprovals(ApprovalFixtures.choices.tree).single()
        assertEquals("command_execution", view.name)
        assertEquals("The command reaches outside the workspace sandbox.", view.reason)
        assertEquals("/w/pipeline", view.cwd)
        assertEquals("https://registry.example.test", view.network)
        assertEquals(listOf("accept", "acceptForSession", "decline"), view.choices.map { it.choiceId })
        assertEquals("Until the session ends", view.choices[1].description)
        assertNull(view.requested)
    }

    @Test fun questionPagingHelpersFollowTheWeb() {
        val q = pendingQuestions(ApprovalFixtures.question.tree).single()
        val (db, env) = q.prompts
        assertEquals(listOf("Postgres"), togglePick(emptyList(), db, "Postgres"))
        assertEquals(listOf("SQLite"), togglePick(listOf("Postgres"), db, "SQLite"))
        assertEquals(emptyList<String>(), togglePick(listOf("Postgres"), db, "Postgres"))
        assertEquals(listOf("staging", "production"), togglePick(listOf("staging"), env, "production"))
        assertFalse(isPromptAnswered(db, emptyMap(), mapOf(Q_DB to "  ")))
        assertTrue(isPromptAnswered(db, emptyMap(), mapOf(Q_DB to "Mongo")))
    }

    @Test fun aNonStringRequestIdStillShowsItsCardUnderTheMapKey() {
        // I4: the reducer keys the map by String(requestId); the card uses that key.
        val tree = foldTree(ApprovalFixtures.write.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", 42); put("toolId", "t-42"); put("name", "Bash"); putJsonObject("input") { put("command", "ls") }
        })
        val views = pendingApprovals(tree)
        assertEquals(listOf("req-w", "42"), views.map { it.requestId })
    }

    @Test fun theCardIdentityIgnoresTheServerButTheWireFingerprintDoesNot() {
        val v = pendingApprovals(ApprovalFixtures.write.tree).single()
        assertEquals(com.tether.app.client.ConsentGuard.cardIdentity("s1", v.activeTurnId, v.request), v.contentFp)
        assertTrue(wireFingerprint(TEST_ORIGIN, v.activeTurnId, v.request) != wireFingerprint("http://other:1", v.activeTurnId, v.request))
        assertEquals(v.contentFp, pendingApprovals(ApprovalFixtures.write.tree).single().contentFp)
        val wider = foldTree(ApprovalFixtures.write.tree, ev("approval_request", "t1", ts = 1) {
            put("requestId", "req-w"); put("toolId", "toolu_w"); put("name", "Write")
            putJsonObject("input") { put("file_path", "/etc/passwd"); put("content", "x") }
        })
        val w = pendingApprovals(wider).single()
        assertTrue(v.contentFp != w.contentFp)
        // The lazy key carries it: the re-raised request is a different row.
        assertTrue(ChatItem.Approval(v).key != ChatItem.Approval(w).key)
    }

    @Test fun aRestoredStoreEvictsItsOldestRecordFirst() {
        // L-2: encode, decode, write once more: the first record written is the one that goes.
        val store = CardStateStore()
        repeat(CardStateStore.MAX_RECORDS) { store.setGrant("r$it", GrantSelection(networkOff = true)) }
        val back = CardStateStore.decode(store.encode())
        back.setGrant("new", GrantSelection(networkOff = true))
        assertEquals(GrantSelection(), back.grant("r0"))
        assertEquals(GrantSelection(networkOff = true), back.grant("r1"))
        assertEquals(GrantSelection(networkOff = true), back.grant("new"))
    }

    @Test fun generationsAreNeverReused() {
        // I-6: "no record" after an eviction is not the "no record" a confirmation was made at.
        val store = CardStateStore()
        val before = store.grantGeneration("a")
        store.setGrant("a", GrantSelection(networkOff = true))
        val written = store.grantGeneration("a")
        assertTrue(written != before)
        repeat(CardStateStore.MAX_RECORDS) { store.setGrant("o$it", GrantSelection()) } // evicts "a"
        val evicted = store.grantGeneration("a")
        assertTrue(evicted != before && evicted != written)
        store.clear()
        assertTrue(store.grantGeneration("a") != evicted)
    }

    @Test fun theStoreBelongsToTheConfiguredServer() {
        // I-2: another server empties it; the same one (a drop, a reconnect) keeps it; a restore keeps the binding.
        val store = CardStateStore()
        store.bindTo("https://a")
        store.setGrant("g", GrantSelection(networkOff = true))
        store.bindTo("https://a")
        assertEquals(GrantSelection(networkOff = true), store.grant("g"))
        val back = CardStateStore.decode(store.encode())
        back.bindTo("https://a")
        assertEquals(GrantSelection(networkOff = true), back.grant("g"))
        back.bindTo("https://b")
        assertEquals(GrantSelection(), back.grant("g"))
    }

    @Test fun savedOtherTextHasATotalBudgetOldestFirst() {
        // I-5: past the budget the OLDEST records' text goes, never the card being typed in.
        val store = CardStateStore()
        val chunk = "x".repeat(3_900)
        repeat(20) { store.setQuestion("q$it", QuestionSelection(other = mapOf(0 to chunk))) }
        val total = (0 until 20).sumOf { store.question("q$it").other.values.sumOf { v -> v.length } }
        assertTrue("total $total", total <= CardStateStore.MAX_OTHER_TOTAL)
        assertTrue(store.question("q0").other.isEmpty())
        assertEquals(chunk, store.question("q19").other[0])
    }

    @Test fun cutsNeverSplitACodePoint() {
        // I-3: a 4000 cut that lands inside a surrogate pair keeps the pair out whole.
        val s = "a".repeat(3_999) + "😀" + "b"
        val cut = cutCodePoints(s, 4_000)
        assertEquals(3_999, cut.length)
        assertTrue(!Character.isHighSurrogate(cut.last()))
        assertEquals("abc", cutCodePoints("abc", 4_000))
    }

    @Test fun displayPathEscapesCutsAndQuotes() {
        assertEquals(iso("“/a\\u000Ab”"), displayPath("/a\nb"))
        assertEquals(iso("“\\u202Egnp.exe”"), displayPath("\u202Egnp.exe"))
        assertEquals(iso("“\\u2028\\u2066\\u0085”"), displayPath("\u2028\u2066\u0085"))
        // L-B: the middle goes; the head AND the scope-deciding tail stay.
        val long = displayPath("/srv/" + "p".repeat(500) + "/tail/etc")
        assertTrue(long, long.startsWith(FSI + "“/srv/ppp"))
        assertTrue(long, long.endsWith("pp/tail/etc”" + PDI))
        assertTrue(long, long.contains("…"))
        assertEquals(DISPLAY_PATH_MAX + 4, long.length) // + the two quotes and FSI / PDI
        assertEquals(iso("“/x; no network access”"), displayPath("/x; no network access"))
        // L-C: by category, plus the listed look-alikes.
        assertEquals(iso("“data\\u200B”"), displayPath("data\u200B")) // FORMAT (zero-width space)
        assertEquals(iso("“a\\u{E0041}b”"), displayPath("a\uDB40\uDC41b")) // a tag character (FORMAT, astral)
        assertEquals(iso("“a\\u00A0b”"), displayPath("a\u00A0b")) // a space that is not U+0020
        assertEquals(iso("“a b”"), displayPath("a b"))
        assertEquals(iso("“/fake\\u201D; network access; read \\u201C/y”"), displayPath("/fake\u201D; network access; read \u201C/y"))
        assertEquals(iso("“a\\u005Cu0041”"), displayPath("a\\u0041")) // a literal backslash cannot fake an escape
        assertEquals(iso("“\\u3164x\\uFE0F\\uE000”"), displayPath("\u3164x\uFE0F\uE000")) // Hangul filler, variation selector, private use
        assertEquals(iso("“\\uD800”"), displayPath("\uD800")) // a lone surrogate
    }

    private fun iso(s: String) = "$FSI$s$PDI"

    @Test fun aPathWithRelativeSegmentsIsShownWholeAndMarked() {
        // Round 7: elided, this would read as a deep directory under /work/proj/src.
        val path = "/work/proj/src/" + "d/".repeat(23) + "../".repeat(26) + "home/op/.ssh/authorized_keys"
        val shown = displayPath(path)
        assertTrue(shown, !shown.contains("…"))
        assertTrue(shown, shown.contains("../".repeat(26) + "home/op/.ssh/authorized_keys"))
        assertTrue(shown, shown.endsWith(RELATIVE_MARKER))
        assertTrue(displayPath("./x").endsWith(RELATIVE_MARKER))
        assertTrue(displayPath("a\\..\\b").endsWith(RELATIVE_MARKER))
        assertTrue(!displayPath("/a/..b/c.d/...").endsWith(RELATIVE_MARKER)) // not a . or .. segment
    }

    @Test fun roundSevenEscapeClasses() {
        for (cp in listOf(0x034F, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180F)) assertTrue("U+%04X default-ignorable".format(cp), needsEscape(cp))
        assertTrue(needsEscape(0x2800)) // braille blank
        assertTrue(needsEscape(0x00AB) && needsEscape(0x00BB) && needsEscape(0x2039)) // Pi / Pf quotes
        for (cp in listOf(0x02EE, 0x2033, 0x201E, 0x201F, 0xFF02)) assertTrue("U+%04X quote look-alike".format(cp), needsEscape(cp))
        assertTrue(needsEscape(0x2026)) // a fake elision mark
        assertEquals(iso("“a\\u2026b”"), displayPath("a…b"))
        assertTrue(!needsEscape('a'.code) && !needsEscape('/'.code) && !needsEscape(' '.code) && !needsEscape('é'.code))
    }

    @Test fun eachPathIsItsOwnBidiIsland() {
        // RTL letters inside a path cannot reorder the separators around it.
        val shown = grantSummary(listOf("/\u05D0\u05D1/x", "/y"), emptyList(), true)
        assertEquals("Confirm these permissions: read ${iso("“/\u05D0\u05D1/x”")}, ${iso("“/y”")}; network access.", shown)
        assertEquals(iso("host\\u202E.example"), displayText("host\u202E.example"))
    }

    // ---- Round 8: a bounded display, and a grant only for what is shown in full ----

    private val tag = "󠁁" // U+E0041, escaped as the 9 characters \u{E0041}

    @Test fun aRelativePathIsWholeUpToTheCapMeasuredAfterEscaping() {
        // "../" (3) + 113 tag characters (1017) + "aaaa" (4) = 1024 escaped characters: shown whole.
        val under = "../" + tag.repeat(113) + "aaaa"
        val atCap = showPath(under)
        assertTrue(atCap.complete)
        assertTrue(!atCap.text.contains("…"))
        assertEquals(iso("“../" + "\\u{E0041}".repeat(113) + "aaaa”") + RELATIVE_MARKER, atCap.text)
        // One more character: over the cap, head…tail, still quoted, isolated and marked; incomplete.
        val over = showPath(under + "b")
        assertTrue(!over.complete)
        assertTrue(over.text, over.text.startsWith("$FSI“../\\u{E0041}"))
        assertTrue(over.text, over.text.endsWith("\\u{E0041}aaaab”$PDI$RELATIVE_MARKER"))
        assertTrue(over.text, over.text.contains("…"))
        // Cut at escape boundaries: every escape shown is whole.
        val body = over.text.removePrefix("$FSI“").substringBefore("”$PDI")
        for (half in body.split("…")) assertTrue(half, half.replace("\\u{E0041}", "").replace("../", "").replace("aaaab", "").isEmpty())
        assertTrue(body.length <= DISPLAY_PATH_RELATIVE_HEAD + 1 + DISPLAY_PATH_RELATIVE_TAIL)
        // In plain characters too (the cap is not in code points).
        assertTrue(showPath("../" + "a".repeat(1_021)).complete)
        assertTrue(!showPath("../" + "a".repeat(1_022)).complete)
    }

    @Test fun aLongWorkingDirectoryKeepsItsRelativeTail() {
        val cwd = "/w/" + "d".repeat(4_000) + "/../.."
        val shown = displayPath(cwd)
        assertTrue(shown, shown.endsWith("dd/../..”$PDI$RELATIVE_MARKER"))
        assertTrue(shown, shown.startsWith("$FSI“/w/ddd"))
        assertTrue(shown.length < DISPLAY_PATH_RELATIVE_MAX + 100)
    }

    @Test fun contextTextIsEscapedFirstThenCutWithARealEllipsis() {
        // 300 tag characters escape to 2700 characters: cut to the last whole escape within 2000 (222 of them).
        val shown = displayText(tag.repeat(300))
        assertEquals("$FSI" + "\\u{E0041}".repeat(DISPLAY_TEXT_MAX / 9) + "…$PDI", shown)
        // Astral characters shown as themselves are never split, whatever the budget (here an odd one).
        val emoji = displayText("😀".repeat(1_500), max = 1_999)
        val body = emoji.removePrefix("$FSI").removeSuffix("…$PDI")
        assertEquals(999 * 2, body.length)
        assertTrue(body.codePoints().allMatch { it == 0x1F600 })
        // A literal "…" in the text is escaped, so the real one is the only ellipsis.
        assertEquals("$FSI\\u2026$PDI", displayText("…"))
        assertEquals("$FSI${"a".repeat(DISPLAY_TEXT_MAX)}$PDI", displayText("a".repeat(DISPLAY_TEXT_MAX)))
    }

    @Test fun aReasonAndAWorkingDirectoryReachTheViewUncut() {
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "r"); put("toolId", "x"); put("name", "command_execution")
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "command")
                    put("reason", "r".repeat(2_000)); put("cwd", "/w/" + "d".repeat(4_000) + "/../..")
                }
            },
        )
        val view = pendingApprovals(tree).single()
        // The reducer's own bounds (2000 / 4096) are the only cut before the card escapes them.
        assertEquals("r".repeat(2_000), view.reason)
        assertEquals("/w/" + "d".repeat(4_000) + "/../..", view.cwd)
    }

    private fun requested(read: List<String>, write: List<String> = emptyList()) =
        RequestedPermissionsView(read = read, write = write, network = false, exact = GrantedPermissions())

    @Test fun rowsWithinTheBudgetAreAllShownAndGrantable() {
        val rows = grantRows(requested((0 until 64).map { "/srv/data/file-$it" }, (0 until 64).map { "/w/out/report-$it" }))
        assertEquals(64, rows.read.size)
        assertEquals(64, rows.write.size)
        assertEquals(0, rows.hidden)
        assertTrue(rows.grantable)
        assertEquals(null, rows.refusal)
        assertEquals(displayPath("/srv/data/file-3"), rows.shown["/srv/data/file-3"])
    }

    @Test fun rowsPastTheBudgetAreCountedAndTheCardIsDenyOnly() {
        // Plain (non-relative) 4096-tag-character paths: each is elided to 160 escapes (~1.6k characters).
        val paths = (0 until 64).map { "/p$it/" + tag.repeat(4_090) }
        val rows = grantRows(requested(paths, paths.map { "/w$it" + it }))
        val shownChars = (rows.read + rows.write).sumOf { it.second.length }
        assertTrue(shownChars <= CARD_PATH_BUDGET)
        assertEquals(128, rows.read.size + rows.write.size + rows.hidden)
        assertTrue(rows.hidden > 0)
        assertTrue(!rows.grantable)
        assertEquals(TOO_MANY_COPY, rows.refusal)
    }

    @Test fun theBudgetBoundaryIsExact() {
        // Round 9: 128 plain paths of 121 characters, each shown as 125 (+ quotes, FSI, PDI): exactly 16,000.
        fun paths(side: Char, extra: Int = 0) = (0 until 64).map { i ->
            val head = "/$side${"%03d".format(i)}/"
            head + "a".repeat(121 - head.length + if (i == 63) extra else 0)
        }
        val at = grantRows(requested(paths('r'), paths('w')))
        assertEquals(CARD_PATH_BUDGET, (at.read + at.write).sumOf { it.second.length })
        assertEquals(0, at.hidden)
        assertTrue(at.grantable)
        // One character more, on the last row: that row crosses the budget and is left out.
        val over = grantRows(requested(paths('r'), paths('w', extra = 1)))
        assertEquals(1, over.hidden)
        assertEquals(127, over.read.size + over.write.size)
        assertTrue(!over.grantable)
        assertEquals(TOO_MANY_COPY, over.refusal)
    }

    @Test fun aDenialTargetIsNeverCutInsideASurrogatePair() {
        val target = denialTarget(com.tether.app.protocol.tree.JsStr("a".repeat(DENIAL_TARGET_MAX - 1) + "😀" + "b"))!!
        assertEquals("a".repeat(DENIAL_TARGET_MAX - 1) + "…", target.value)
        assertEquals("abc", denialTarget(com.tether.app.protocol.tree.JsStr("abc"))!!.value)
    }

    @Test fun anIncompleteRelativePathMakesTheCardDenyOnly() {
        val rows = grantRows(requested(listOf("/srv/a", "../" + "a".repeat(1_022))))
        assertEquals(2, rows.read.size)
        assertEquals(0, rows.hidden)
        assertTrue(!rows.grantable)
        assertEquals(TOO_LONG_COPY, rows.refusal)
    }

    @Test fun aGrantChoiceIsNotOfferedWhenNotGrantable() {
        val view = pendingApprovals(ApprovalFixtures.grants.tree).single()
        val exact = view.choices.first { it.permissionGrant == "exact" }
        val subset = view.choices.first { it.permissionGrant == "subset" }
        val deny = view.choices.first { it.permissionGrant == null }
        val all = GrantedPermissions(fileSystemRead = listOf("/srv/fixtures", "/srv/schema.sql"), fileSystemWrite = listOf("/w/report"), networkEnabled = true)
        assertTrue(pickFor(view, exact, confirmed = true, subset = all) != null)
        assertEquals(null, pickFor(view, exact, confirmed = true, subset = all, grantable = false))
        assertEquals(null, pickFor(view, subset, confirmed = true, subset = all, grantable = false))
        assertEquals("deny", pickFor(view, deny, confirmed = false, subset = null, grantable = false)?.choiceId)
    }

    private companion object {
        const val Q_DB = ApprovalFixtures.Q_DB
    }
}
