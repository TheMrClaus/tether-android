package com.tether.app.ui.chat

import com.tether.app.protocol.GrantedPermissions
import com.tether.app.protocol.model.AgentSession
import com.tether.app.protocol.reduce.ev
import com.tether.app.protocol.reduce.foldTree
import com.tether.app.protocol.model.LegacyProjectionAdapter
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        assertEquals(RunRef("t1::task-1", "Check fixtures", "child-read"), child.run)
        assertEquals(DenialTarget("File", "/srv/fixtures/secret.env"), child.target)
        assertEquals(DenialTarget("Command", "rm -rf build/ && git clean -fdx"), denial.first { it.denial.toolId == "toolu_b" }.target)
        assertNull(denial.first { it.denial.toolId == "toolu_b" }.run)
        assertEquals(denial.map { it.key }.distinct(), denial.map { it.key })
    }

    @Test fun theLockNamesWhyInOrder() {
        val s = AgentSession(id = "s1", provider = "claude", name = "n", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
        assertNull(consentLock(connected = true, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, session = s))
        assertEquals(ConsentLock.Offline, consentLock(connected = false, session = s))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = true, session = s.copy(readOnly = true)))
        assertEquals(ConsentLock.HandedOff, consentLock(connected = true, session = s.copy(handedOffTo = "s2")))
        assertEquals(ConsentLock.ReadOnly, consentLock(connected = false, session = s.copy(readOnly = true, handedOffTo = "s2")))
    }

    /**
     * ta-coik.57: both ChatScreen paths (ChatScreen.kt controlLock, stopLock) hand the lock to a key only
     * through [commandKeyLock] (proved before the dead "Catching up" lock was deleted: it never reached a key). Over every link / read-only / handed-off combination, what
     * reaches a key is nothing, Read-only or Handed-off: never a "Catching up" (or offline) lock, as on
     * the web (chat-view.tsx 29537e0 draws these keys live whatever the link).
     */
    @Test fun noCatchingUpOrOfflineLockEverReachesAKey() {
        val s = AgentSession(id = "s1", provider = "claude", name = "n", cwd = "/w", status = "active", startedAt = 1, updatedAt = 1)
        for (connected in listOf(true, false)) for (readOnly in listOf(true, false)) for (handedOff in listOf(null, "s2")) {
            val session = s.copy(readOnly = readOnly, handedOffTo = handedOff)
            val reaching = commandKeyLock(consentLock(connected, session))
            assertTrue("connected=$connected readOnly=$readOnly handedOff=$handedOff -> $reaching", reaching == null || reaching.name == "ReadOnly" || reaching.name == "HandedOff")
            if (!readOnly && handedOff == null) assertNull("a healthy session's keys are never locked", reaching)
        }
        assertNull(commandKeyLock(null))
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
        // chat-view.tsx 90fbb9f :1191-1205 (ta-coik.5): "exact" needs its confirmation and grants the request
        // as it came; "subset" grants what is ticked, with no confirmation.
        assertNull(pickFor(view, all, confirmed = false, subset = full))
        assertEquals(ApprovalPick("some", subset), pickFor(view, some, confirmed = false, subset = subset))
        assertEquals(ApprovalPick("some", full), pickFor(view, some, confirmed = false, subset = full))
        assertEquals(ApprovalPick("all", requested.exact), pickFor(view, all, confirmed = true, subset = full))
        assertEquals("whatever is ticked", ApprovalPick("all", requested.exact), pickFor(view, all, confirmed = true, subset = subset))
        assertNull("a subset needs something ticked", pickFor(view, some, confirmed = true, subset = subsetGrant(emptySet(), emptySet(), false)))
        assertEquals(ApprovalPick("deny", null), pickFor(view, deny, confirmed = false, subset = subset))
        assertEquals("Confirm the complete permission expansion shown above.", EXACT_CONFIRM_COPY)
        assertTrue("the box is drawn for an exact choice", view.needsConfirm)
        val subsetOnly = view.copy(choices = view.choices.filter { it.permissionGrant != "exact" })
        assertFalse("no box without an exact choice (:1270)", subsetOnly.needsConfirm)
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
        assertEquals(isoPath("“/a\\u000Ab”"), displayPath("/a\nb"))
        assertEquals(isoPath("“\\u202Egnp.exe”"), displayPath("\u202Egnp.exe"))
        assertEquals(isoPath("“\\u2028\\u2066\\u0085”"), displayPath("\u2028\u2066\u0085"))
        // L-B: the middle goes; the head AND the scope-deciding tail stay.
        val long = displayPath("/srv/" + "p".repeat(500) + "/tail/etc")
        assertTrue(long, long.startsWith(LRI + "“/srv/ppp"))
        assertTrue(long, long.endsWith("pp/tail/etc”" + PDI))
        assertTrue(long, long.contains("…"))
        assertEquals(DISPLAY_PATH_MAX + 4, long.length) // + the two quotes and LRI / PDI
        assertEquals(isoPath("“/x; no network access”"), displayPath("/x; no network access"))
        // L-C: by category, plus the listed look-alikes.
        assertEquals(isoPath("“data\\u200B”"), displayPath("data\u200B")) // FORMAT (zero-width space)
        assertEquals(isoPath("“a\\u{E0041}b”"), displayPath("a\uDB40\uDC41b")) // a tag character (FORMAT, astral)
        assertEquals(isoPath("“a\\u00A0b”"), displayPath("a\u00A0b")) // a space that is not U+0020
        assertEquals(isoPath("“a b”"), displayPath("a b"))
        assertEquals(isoPath("“/fake\\u201D; network access; read \\u201C/y”"), displayPath("/fake\u201D; network access; read \u201C/y"))
        assertEquals(isoPath("“a\\u005Cu0041”"), displayPath("a\\u0041")) // a literal backslash cannot fake an escape
        assertEquals(isoPath("“\\u3164x\\uFE0F\\uE000”"), displayPath("\u3164x\uFE0F\uE000")) // Hangul filler, variation selector, private use
        assertEquals(isoPath("“\\uD800”"), displayPath("\uD800")) // a lone surrogate
    }

    private fun iso(s: String) = "$FSI$s$PDI"

    /** A shown PATH is an LRI island. */
    private fun isoPath(s: String) = "$LRI$s$PDI"

    @Test fun aPathWithRelativeSegmentsIsShownWholeAndMarked() {
        // Round 7: elided, this would read as a deep directory under /work/proj/src.
        val path = "/work/proj/src/" + "d/".repeat(23) + "../".repeat(26) + "home/op/.ssh/authorized_keys"
        val shown = displayPath(path)
        assertTrue(shown, !shown.contains("…"))
        assertTrue(shown, shown.contains("../".repeat(26) + "home/op/.ssh/authorized_keys"))
        assertTrue(shown, shown.endsWith(RELATIVE_MARKER))
        assertTrue(displayPath("./x").endsWith(RELATIVE_MARKER))
        assertTrue(displayPath("a\\..\\b").endsWith(RELATIVE_MARKER))
        assertTrue(!displayPath("/a/..b/c.d/.e").endsWith(RELATIVE_MARKER)) // not a . or .. segment
    }

    @Test fun roundSevenEscapeClasses() {
        for (cp in listOf(0x034F, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180F)) assertTrue("U+%04X default-ignorable".format(cp), needsEscape(cp))
        assertTrue(needsEscape(0x2800)) // braille blank
        assertTrue(needsEscape(0x00AB) && needsEscape(0x00BB) && needsEscape(0x2039)) // Pi / Pf quotes
        for (cp in listOf(0x02EE, 0x2033, 0x201E, 0x201F, 0xFF02)) assertTrue("U+%04X quote look-alike".format(cp), needsEscape(cp))
        assertTrue(needsEscape(0x2026)) // a fake elision mark
        assertEquals(isoPath("“a\\u2026b”"), displayPath("a…b"))
        assertTrue(!needsEscape('a'.code) && !needsEscape('/'.code) && !needsEscape(' '.code) && !needsEscape('é'.code))
    }

    @Test fun eachPathIsItsOwnBidiIsland() {
        // RTL letters inside a path cannot reorder the separators around it.
        assertEquals(isoPath("“/\u05D0\u05D1/x”"), displayPath("/\u05D0\u05D1/x"))
        assertEquals(iso("host\\u202E.example"), displayText("host\u202E.example"))
    }

    // ---- ta-57l: nothing limits a card but the reducer; display hardening ----

    private val tag = "󠁁" // U+E0041, escaped as the 9 characters \u{E0041}

    @Test fun aLongWorkingDirectoryKeepsItsRelativeTail() {
        val cwd = "/w/" + "d".repeat(4_000) + "/../.."
        val shown = displayPath(cwd)
        // A relative path is never cut: the whole directory and its tail are shown.
        assertEquals(isoPath("“$cwd”") + RELATIVE_MARKER, shown)
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

    @Test fun aDenialTargetIsNeverCutInsideASurrogatePair() {
        val target = denialTarget(com.tether.app.protocol.tree.JsStr("a".repeat(DENIAL_TARGET_MAX - 1) + "😀" + "b"))!!
        assertEquals("a".repeat(DENIAL_TARGET_MAX - 1) + "…", target.value)
        assertEquals("abc", denialTarget(com.tether.app.protocol.tree.JsStr("abc"))!!.value)
    }

    @Test fun everyRequestedPathReachesTheViewWholeWhateverItsLength() {
        // Past the old 1024-escaped-character cap and the old 16,000-character budget: all 128 paths, raw.
        val read = (0 until 64).map { "/r$it/../" + tag.repeat(4_000) }
        val write = (0 until 64).map { "/w$it/" + tag.repeat(4_000) }
        val tree = foldTree(com.tether.app.protocol.reduce.freshTree(),
            ev("turn_started", "t1", ts = 1) { put("idempotencyKey", "k1") },
            ev("approval_request", "t1", ts = 1) {
                put("requestId", "r"); put("toolId", "x"); put("name", "permissions")
                putJsonObject("metadata") {
                    put("provider", "codex"); put("kind", "permissions")
                    putJsonObject("requestedPermissions") {
                        putJsonObject("fileSystem") {
                            putJsonArray("read") { read.forEach { add(it) } }
                            putJsonArray("write") { write.forEach { add(it) } }
                        }
                    }
                }
            },
        )
        val view = pendingApprovals(tree).single().requested!!
        assertEquals(read, view.read)
        assertEquals(write, view.write)
        // A relative path is shown whole (no elision); a plain one keeps the usual head...tail.
        assertTrue(!displayPath(read[0]).contains("…"))
        assertTrue(displayPath(write[5]).contains("…"))
    }

    @Test fun aPathInPiecesHoldsExactlyTheTextOfDisplayPath() {
        val tag = "\uDB40\uDC41"
        for (path in listOf("/a/b", "/" + "x".repeat(500), "../" + "a".repeat(4_000) + "/../etc", "/p/../" + tag.repeat(4_000), "/p/" + tag.repeat(4_000))) {
            val whole = displayPath(path)
            assertEquals(listOf(whole), displayPathChunks(path, Int.MAX_VALUE))
            val pieces = displayPathChunks(path)
            // Strip each piece's own island marks and the relative marker: the concatenation is the whole.
            val body = pieces.joinToString("") { it.removePrefix("$LRI").removeSuffix(RELATIVE_MARKER).removeSuffix("$PDI") }
            assertEquals(whole.removePrefix("$LRI").removeSuffix(RELATIVE_MARKER).removeSuffix("$PDI"), body)
            // Each piece is its own balanced island, cut at an escape boundary, within the bound.
            for (p in pieces) {
                assertTrue(p.startsWith("$LRI") && (p.endsWith("$PDI") || p.endsWith("$PDI$RELATIVE_MARKER")))
                assertTrue(p.length <= GRANT_CHUNK_CHARS + 2 + RELATIVE_MARKER.length + 12)
            }
            if (hasRelativeSegment(path)) assertTrue(pieces.last().endsWith(RELATIVE_MARKER))
        }
        assertEquals(1, displayPathChunks("/srv/data/file-1").size)
        assertTrue(displayPathChunks("/" + tag.repeat(4_000)).size <= 2) // plain: elided to 160 escapes (1,440 characters)
        assertTrue(displayPathChunks("/p/../" + tag.repeat(4_000)).size > 25) // relative: whole, ~36k characters
        // No escape is split across pieces: strip the islands, quotes, marker and the head, only whole escapes are left.
        for (p in displayPathChunks("/p/../" + tag.repeat(4_000))) {
            val left = p.removePrefix("$LRI").removeSuffix(RELATIVE_MARKER).removeSuffix("$PDI").replace("“", "").replace("”", "").replace("/p/../", "")
            assertTrue(p, left.replace("\\u{E0041}", "").isEmpty())
        }
    }

    @Test fun aRelativePathIsNeverCutHoweverLong() {
        val path = "../" + "a".repeat(4_000) + "/../etc"
        assertEquals(isoPath("“$path”") + RELATIVE_MARKER, displayPath(path))
    }

    @Test fun everyChoiceIsOfferedWhateverThePaths() {
        val view = pendingApprovals(ApprovalFixtures.grants.tree).single()
        val exact = view.choices.first { it.permissionGrant == "exact" }
        val subset = view.choices.first { it.permissionGrant == "subset" }
        val deny = view.choices.first { it.permissionGrant == null }
        val all = GrantedPermissions(fileSystemRead = listOf("/srv/fixtures", "../" + "a".repeat(4_000)), fileSystemWrite = listOf("/w/report"), networkEnabled = true)
        assertEquals(ApprovalPick(exact.choiceId, view.requested!!.exact), pickFor(view, exact, confirmed = true, subset = all))
        assertEquals(ApprovalPick(subset.choiceId, all), pickFor(view, subset, confirmed = true, subset = all))
        assertEquals("deny", pickFor(view, deny, confirmed = false, subset = null)?.choiceId)
    }

    // ---- Low-3: stacked combining marks ----

    private fun shownIn(s: String) = displayPath(s).removePrefix("$LRI“").removeSuffix("”$PDI")

    @Test fun anNfdVietnamesePathStaysUnescaped() {
        // macOS stores NFD: a base with a dot below AND a circumflex is two marks.
        val nfd = java.text.Normalizer.normalize("/Users/an/Tiếng Việt/phở-ệ-ữ-ặ.txt", java.text.Normalizer.Form.NFD)
        assertTrue("fixture is decomposed", nfd.length > "/Users/an/Tiếng Việt/phở-ệ-ữ-ặ.txt".length)
        assertEquals(nfd, shownIn(nfd))
        val path = "/e\u0323\u0302/o\u0302\u0309"
        assertEquals(path, shownIn(path))
    }

    @Test fun aHindiClusterStaysUnescaped() {
        // Mc and Mn marks (a vowel sign with an anusvara, a virama): never three in a row.
        for (path in listOf("/home/\u0939\u093F\u0928\u094D\u0926\u0940/f", "/d/\u0915\u094D\u0937\u0924\u094D\u0930\u093F\u092F", "/d/\u0938\u094D\u0924\u094D\u0930\u0940\u0902")) {
            assertEquals(path, shownIn(path))
        }
    }

    @Test fun aStackOfMarksIsEscapedFromTheThird() {
        val marks = "\u0301\u0302\u0303\u0304\u0305\u0306" // six combining marks on one base
        assertEquals("/a\u0301\u0302\\u0303\\u0304\\u0305\\u0306", shownIn("/a$marks"))
        // A new base starts a new run.
        assertEquals("a\u0301\u0302\\u0303b\u0301\u0302", shownIn("a\u0301\u0302\u0303b\u0301\u0302"))
    }

    @Test fun everyEnclosingMarkIsEscaped() {
        assertEquals("a\\u20DD", shownIn("a\u20DD"))
        assertEquals("a\\u20DD\\u20DE", shownIn("a\u20DD\u20DE"))
    }

    @Test fun aMarkRunIsCountedAcrossACut() {
        // A path long enough to be elided: the tail starts inside the run and the run still counts.
        val path = "/" + "x".repeat(300) + "e" + "\u0301".repeat(6)
        val shown = shownIn(path)
        assertTrue(shown, shown.endsWith("e\u0301\u0301" + "\\u0301".repeat(4)))
    }

    @Test fun theLookAlikesAreEscaped() {
        for (cp in listOf(0x2036, 0x02DD, 0x02BA, 0x05F4, 0x301D, 0x301E, 0x301F, 0x3003, 0x2025, 0x22EF, 0xFE19, 0x1D159)) {
            assertTrue("U+%04X".format(cp), needsEscape(cp))
        }
        assertEquals(isoPath("“a\\u2025b”"), displayPath("a\u2025b"))
        assertEquals(isoPath("“\\u{1D159}”"), displayPath(String(Character.toChars(0x1D159))))
    }

    @Test fun anRtlFirstPathIsAnLriIslandSoItsQuotesDoNotFlip() {
        val shown = displayPath("\u05D0\u05D1/x")
        assertTrue(shown, shown.startsWith("$LRI“") && shown.endsWith("”$PDI"))
        assertTrue(!shown.contains(FSI))
        assertEquals('\u2066', LRI)
    }

    @Test fun windowsRelativeFormsCountAsRelative() {
        for (p in listOf("C:..", "C:..\\x", "d:.\\x", "/a/.../b", "/a/.. /b", "..\\x", "/a/c:../b")) {
            assertTrue(p, hasRelativeSegment(p))
            assertTrue(p, displayPath(p).endsWith(RELATIVE_MARKER))
        }
        for (p in listOf("/a/..b/c.d", "C:\\x", "C:/dir", "/a/b..c", "/a/.hidden", "/a/b.", "/a/ ..x")) assertTrue(p, !hasRelativeSegment(p))
    }

    @Test fun displayLineEscapesThenCutsWithNoIsolate() {
        assertEquals("Ba\\u202Esh", displayLine("Ba\u202Esh"))
        assertEquals("a".repeat(200), displayLine("a".repeat(200), 200))
        assertEquals("a".repeat(200) + "…", displayLine("a".repeat(201), 200))
        assertEquals("\\u{E0041}".repeat(22) + "…", displayLine(tag.repeat(50), 200))
    }

    private companion object {
        const val Q_DB = ApprovalFixtures.Q_DB
    }
}
