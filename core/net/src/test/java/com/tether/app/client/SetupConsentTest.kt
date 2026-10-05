package com.tether.app.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-m7ef: the pure core of the consent port (lib/draft-form.ts setupConsentStep, scheduleConsentStep,
 * revealHiddenCharacters, setupCheckErrorApplies; lib/hidden-characters.mjs), each against the cases
 * tests/setup-consent-composer.test.mjs and tests/worktree-setup-consent.test.mjs pin on the web (1bf4a465).
 */
class SetupConsentTest {
    private val digest = "sha256:" + "ab".repeat(32)

    private fun json(text: String): JsonObject = com.tether.app.protocol.TetherJson.parseToJsonElement(text).jsonObject

    // --- hidden characters -------------------------------------------------------------------------

    @Test
    fun aHookCommandRevealsEveryCodePointOutsidePrintableAscii() {
        // Cyrillic "а" (homoglyph), a right-to-left override, a zero-width space, an accented letter, a no-break space.
        val text = "echo аpi ‮ok​ café x"
        val segments = HiddenCharacters.reveal(text, command = true)
        assertEquals(
            listOf("echo ", "U+0430", "pi ", "U+202E", "ok", "U+200B", " caf", "U+00E9", "U+00A0", "x"),
            segments.map { it.text },
        )
        assertEquals(listOf(false, true, false, true, false, true, false, true, true, false), segments.map { it.hidden })
        assertEquals("nothing of the original is rendered raw", "echo U+0430pi U+202Eok" + "U+200B cafU+00E9U+00A0x", segments.joinToString("") { it.text })
    }

    @Test
    fun tabsNewlinesAndPrintableAsciiAreNeverHidden() {
        val text = "set -e\n\tpnpm install --frozen-lockfile && echo \"done\""
        assertEquals(listOf(CommandSegment(text, hidden = false)), HiddenCharacters.reveal(text, command = true))
        assertEquals(listOf(CommandSegment(text, hidden = false)), HiddenCharacters.reveal(text))
        assertTrue(HiddenCharacters.reveal("").isEmpty())
    }

    @Test
    fun otherTextKeepsTheSharedSetOnly() {
        // A letter with an accent is readable text outside a hook command; controls, bidi marks, tags and a space variant are not.
        val segments = HiddenCharacters.reveal("café a‮b   ️ ⠀ 󠀁 \r", command = false)
        assertEquals(
            listOf("café a", "U+202E", "b ", "U+00A0", " ", "U+FE0F", " ", "U+2800", " ", "U+E0001", " ", "U+000D"),
            segments.map { it.text },
        )
        assertEquals(listOf(false, true, false, true, false, true, false, true, false, true, false, true), segments.map { it.hidden })
    }

    @Test
    fun aSupplementaryCodePointIsOneToken() {
        // U+1F600 is outside printable ASCII: one token for the pair, never two half-labels.
        assertEquals(listOf("a", "U+1F600", "b"), HiddenCharacters.reveal("a😀b", command = true).map { it.text })
    }

    // --- the preview parse -----------------------------------------------------------------------

    @Test
    fun aMalformedPreviewParsesToFailClosedFields() {
        val info = WorktreeSourceInfo.parse(
            json("""{"isRepo":true,"setupPreview":{"mode":"branch-off","remote":"origin","baseRef":"origin/main","commit":"c","commands":"rm -rf /",
                "teardown":[1],"portScript":7,"consent":"none","hiddenCharacters":"yes"}}"""),
        )
        val p = info.setupPreview!!
        assertNull("a string is not a command list", p.commands)
        assertNull("a non-string entry voids the list", p.teardown)
        assertFalse(p.portScriptWellFormed)
        assertFalse("only a boolean true counts", p.hiddenCharacters)
        assertTrue(SetupConsentSteps.create(info) is SetupConsentStep.Refuse)
        assertNull(WorktreeSourceInfo.parse(json("""{"isRepo":true}""")).setupPreview)
        assertNull(WorktreeSourceInfo.parse(json("""{"isRepo":true,"setupPreview":null}""")).setupPreview)
        assertNull(WorktreeSourceInfo.parse(json("""{"isRepo":true,"setupPreview":"x"}""")).setupPreview)
    }

    @Test
    fun anOversizedCommandOrListIsRefusedNeverCut() {
        val huge = "x".repeat(20_000)
        val p = WorktreeSourceInfo.parse(json("""{"isRepo":true,"setupPreview":{"commands":["$huge"],"teardown":[]}}""")).setupPreview!!
        assertNull(p.commands)
        val many = (1..65).joinToString(",") { "\"c$it\"" }
        assertNull(WorktreeSourceInfo.parse(json("""{"isRepo":true,"setupPreview":{"commands":[$many]}}""")).setupPreview!!.commands)
        assertEquals(64, WorktreeSourceInfo.parse(json("""{"isRepo":true,"setupPreview":{"commands":[${many.substringBeforeLast(",")}]}}""")).setupPreview!!.commands!!.size)
    }

    // --- the schedule save -----------------------------------------------------------------------

    private fun scheduleInfo(
        scheduleConsent: String? = digest,
        commands: List<String>? = listOf("pnpm install"),
        portScript: String? = null,
        error: String? = null,
        isRepo: Boolean = true,
    ) = WorktreeSourceInfo(
        isRepo = isRepo,
        setupPreview = WorktreeSetupPreview("branch-off", "origin", "origin/main", "c".repeat(40), commands, emptyList(), portScript, true, null, false, digest, digest, scheduleConsent, error),
    )

    @Test
    fun theScheduleStepSavesWithNoneConfirmsOrRefuses() {
        assertEquals(ScheduleConsentStep.Save("none"), SetupConsentSteps.schedule(scheduleInfo("none", emptyList())))
        val confirm = SetupConsentSteps.schedule(scheduleInfo()) as ScheduleConsentStep.Confirm
        assertEquals(digest, confirm.preview.scheduleConsent)
        assertEquals(listOf("pnpm install"), confirm.preview.commands)
        // A setup-free repository with a port script still needs the approval.
        assertTrue(SetupConsentSteps.schedule(scheduleInfo(commands = emptyList(), portScript = "/p")) is ScheduleConsentStep.Confirm)
        val refusals = listOf(
            scheduleInfo("none", listOf("x")),
            scheduleInfo("none", emptyList(), portScript = "/p"),
            scheduleInfo("none", null),
            scheduleInfo(null),
            scheduleInfo("sha256:short"),
            scheduleInfo(commands = null),
            scheduleInfo(error = "No default branch"),
            scheduleInfo(isRepo = false),
        )
        for (info in refusals) assertTrue("$info", SetupConsentSteps.schedule(info) is ScheduleConsentStep.Refuse)
        assertEquals(
            "That folder is not a Git repository, so an isolated schedule cannot run there.",
            (SetupConsentSteps.schedule(scheduleInfo(isRepo = false)) as ScheduleConsentStep.Refuse).message,
        )
        assertEquals("No default branch", (SetupConsentSteps.schedule(scheduleInfo(error = "No default branch")) as ScheduleConsentStep.Refuse).message)
        assertTrue(SetupConsentSteps.schedule(null) is ScheduleConsentStep.Refuse)
    }

    // --- errors that end a check ---------------------------------------------------------------------

    @Test
    fun anErrorEndsTheCheckOnlyWhenNewerAndOursOrTokenless() {
        fun ends(checking: Boolean = true, seq: Long = 5, requestId: String? = null, message: String = "m") =
            SetupConsentSteps.errorEndsCheck(checking, "mine", 4, CreateErrorReply(message, seq, requestId))
        assertTrue(ends())
        assertTrue(ends(requestId = "mine"))
        assertFalse("another request's", ends(requestId = "yours"))
        assertFalse("not newer", ends(seq = 4))
        assertFalse("no words", ends(message = ""))
        assertFalse("not checking", ends(checking = false))
    }

    @Test
    fun theConsentGrammarIsNoneOrASha256Digest() {
        assertTrue(isSetupConsentValue("none"))
        assertTrue(isSetupConsentValue(digest))
        assertFalse(isSetupConsentValue(null))
        assertFalse(isSetupConsentValue("sha256:" + "AB".repeat(32)))
        assertFalse(isSetupConsentValue("sha256:" + "ab".repeat(31)))
        assertFalse(isSetupConsentValue("NONE"))
        assertFalse(isSetupConsentValue(""))
    }

    @Test
    fun autoArchiveIdleDaysReadsTolerantly() {
        fun days(value: String) = ServerSettingsView.of(
            com.tether.app.protocol.ServerMessage.parse("""{"type":"server-settings","settings":{"autoArchiveIdleDays":$value},"envForced":{},"restartRequired":false,"discovered":[],"detected":{}}""")
                as com.tether.app.protocol.ServerMessage.ServerSettings,
        ).autoArchiveIdleDays
        assertEquals(14, days("14"))
        assertEquals(3650, days("3650"))
        assertEquals(0, days("0"))
        assertEquals("beyond 3650 is not a value the server sends", 0, days("3651"))
        assertEquals(0, days("1.5"))
        assertEquals(0, days("\"14\""))
        assertEquals(0, days("null"))
        assertEquals(0, days("-1"))
    }
}
