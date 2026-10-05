package com.tether.app.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-m7ef (tether PR #241, PROTOCOL 143 r3): the end-session half of the consent port: lib/draft-form.ts
 * teardownConsentStep, stopsSessionsNotice, teardownCheckoutMayHaveChanged and the skip copy of
 * lib/protocol.ts, each against the cases tests/teardown-consent-wire.test.mjs pins on the web (1bf4a465).
 */
class TeardownConsentTest {
    private val digest = "sha256:" + "ab".repeat(32)
    private val other = "sha256:" + "cd".repeat(32)

    private fun json(text: String): JsonObject = com.tether.app.protocol.TetherJson.parseToJsonElement(text).jsonObject

    // --- the skip copy ---------------------------------------------------------------------------

    @Test
    fun everySkipReasonHasItsOwnSentenceAndAnUnknownOneStillSaysItDidNotRun() {
        assertEquals(3, WorktreeSkipCopy.SETUP.size)
        assertEquals(
            setOf("consent-missing", "declined", "consent-mismatch", "consent-not-owner", "checkout-tampered", "checkout-changed", "processes-alive", "sessions-changed"),
            WorktreeSkipCopy.TEARDOWN.keys,
        )
        assertEquals(WorktreeSkipCopy.TEARDOWN.size, WorktreeSkipCopy.TEARDOWN.values.toSet().size)
        assertTrue(WorktreeSkipCopy.teardown("processes-alive").contains("could not prove that every process the session started had stopped"))
        assertTrue(WorktreeSkipCopy.teardown("sessions-changed").contains("the other sessions it would have stopped were not the ones shown"))
        assertEquals("This project's teardown did not run.", WorktreeSkipCopy.teardown("from-the-future"))
        assertEquals("This project's setup and teardown did not run.", WorktreeSkipCopy.setup("from-the-future"))
    }

    // --- the end-session teardown ----------------------------------------------------------------

    private fun reply(preview: String?, malformed: Boolean = false) = ArchivePreviewReply("s1", preview?.let { TeardownPreview.parse(json(it)) }, malformed, "r", 1)

    private fun tdJson(
        consent: String? = digest,
        dig: String? = digest,
        commands: String = """["make clean"]""",
        intact: String = "true",
        changed: String = "false",
        error: String = "null",
        stops: String = "[]",
        hidden: String = "false",
    ) = """{"sessionId":"s1","commands":$commands,"commit":"${"c".repeat(40)}","worktreePath":"/w","checkoutIntact":$intact,"checkoutChanged":$changed,
        "hiddenCharacters":$hidden,"fingerprint":null,"nonce":null,"digest":${dig?.let { "\"$it\"" } ?: "null"},"consent":${consent?.let { "\"$it\"" } ?: "null"},
        "error":$error,"stopsSessions":$stops}"""

    @Test
    fun noWorktreeEndsAtOnceAndAMalformedPreviewOnlyOffersToEndWithout() {
        assertEquals(TeardownConsentStep.End("none"), TeardownSteps.teardown(reply(null)))
        assertEquals(TeardownConsentStep.Without(TEARDOWN_CHECK_FAILED_COPY), TeardownSteps.teardown(reply(null, malformed = true)))
    }

    @Test
    fun theTeardownStepEndsConfirmsOrOffersOnlyToEndWithout() {
        assertEquals(TeardownConsentStep.End(), TeardownSteps.teardown(reply(tdJson("none", null, "[]"))))
        val confirm = TeardownSteps.teardown(reply(tdJson())) as TeardownConsentStep.Confirm
        assertEquals(digest, confirm.preview.consent)
        assertEquals(listOf("make clean"), confirm.preview.commands)
        assertFalse(confirm.preview.mayHaveChanged)
        val without = listOf(
            tdJson(error = "\"The checkout is gone.\"") to "The checkout is gone.",
            tdJson("none", null, """["x"]""") to TEARDOWN_CHECK_FAILED_COPY,
            tdJson(intact = "false") to TEARDOWN_TAMPERED_COPY,
            tdJson(intact = "null") to TEARDOWN_TAMPERED_COPY,
            tdJson(consent = other) to TEARDOWN_CHECK_FAILED_COPY,
            tdJson(consent = "sha256:zz", dig = "sha256:zz") to TEARDOWN_CHECK_FAILED_COPY,
            tdJson(consent = null, dig = null) to TEARDOWN_CHECK_FAILED_COPY,
            tdJson(commands = "[]") to TEARDOWN_CHECK_FAILED_COPY,
            tdJson(commands = "\"x\"") to TEARDOWN_CHECK_FAILED_COPY,
        )
        for ((preview, message) in without) assertEquals(preview, TeardownConsentStep.Without(message), TeardownSteps.teardown(reply(preview)))
    }

    @Test
    fun anUnknownCheckoutIsTreatedAsChangedNeverAsUnchanged() {
        fun approval(changed: String) = (TeardownSteps.teardown(reply(tdJson(changed = changed))) as TeardownConsentStep.Confirm).preview
        assertTrue(approval("true").mayHaveChanged)
        assertTrue("null = Tether could not check it", approval("null").mayHaveChanged)
        assertFalse(approval("false").mayHaveChanged)
        assertEquals("a non-boolean reads as unknown", null, TeardownPreview.parse(json(tdJson(changed = "\"no\""))).checkoutChanged)
    }

    @Test
    fun otherSessionsTheEndWouldStopAreNamedAndNeverSilent() {
        val stops = """[{"sessionId":"s2","name":"Home folder"},{"sessionId":"s3","name":"  "},{"sessionId":"s4"},{"name":"no id"}]"""
        val confirm = TeardownSteps.teardown(reply(tdJson(stops = stops))) as TeardownConsentStep.Confirm
        assertEquals(
            "Ending this session also stops 3 other sessions that can write its checkout: Home folder, s3, s4.",
            confirm.preview.stopsSessionsNotice,
        )
        assertEquals(
            "Ending this session also stops another session that can write its checkout: Home folder.",
            TeardownSteps.stopsSessionsNotice(listOf(StoppedSession("s2", "Home folder"))),
        )
        assertNull(TeardownSteps.stopsSessionsNotice(emptyList()))
        // Nothing to run, but other sessions would stop: never an end with no word.
        val none = TeardownSteps.teardown(reply(tdJson("none", null, "[]", stops = """[{"sessionId":"s2","name":"Home"}]""")))
        assertEquals(
            TeardownConsentStep.Without("Ending this session also stops another session that can write its checkout: Home."),
            none,
        )
        // At most 20 are read.
        val many = (1..25).joinToString(",", "[", "]") { """{"sessionId":"s$it","name":"n$it"}""" }
        assertEquals(20, TeardownPreview.parse(json(tdJson(stops = many))).stopsSessions.size)
    }
}
