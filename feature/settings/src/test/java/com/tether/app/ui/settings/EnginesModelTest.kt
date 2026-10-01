package com.tether.app.ui.settings

import com.tether.app.client.EngineCard
import com.tether.app.client.EngineDetection
import com.tether.app.client.ServerSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ta-dh1: the Engines tab's words and its pure rules (what Done asks to confirm, "Use detected"). */
class EnginesModelTest {
    private val view = ServerFixtures.view()

    @Test fun theSubtitleSaysWhatTheScanFound() {
        assertEquals("scanning…", EngineRows.status(null))
        assertEquals("not found", EngineRows.status(EngineDetection(false, "1.0", "user PATH", null, null)))
        assertEquals("installed · bundled", EngineRows.status(EngineDetection(true, null, "bundled", null, null)))
        assertEquals("2.0 · not found", EngineRows.status(EngineDetection(true, "2.0", "somewhere", null, null)))
        // A version is server text: drawn by the value rule, so a hidden character is spelled out.
        assertEquals("2\\u{202E}0 · host install", EngineRows.status(EngineDetection(true, "2\u202E0", "host install", null, null)))
    }

    @Test fun doneAsksOnlyForAnEditThatChangesTheServersValue() {
        val s = ServerSetting.CodexCommand
        // Untouched, or only spaces around the server's value: nothing to confirm.
        assertNull(EngineRows.review(view, EngineCard.Codex, s, "codex", "codex"))
        assertNull(EngineRows.review(view, EngineCard.Codex, s, "  codex ", "codex"))
        assertEquals(EngineEdit(EngineCard.Codex, s, "/opt/codex", trimmed = false), EngineRows.review(view, EngineCard.Codex, s, "/opt/codex", "codex"))
        assertEquals(EngineEdit(EngineCard.Codex, s, "/opt/codex", trimmed = true), EngineRows.review(view, EngineCard.Codex, s, " /opt/codex\t", "codex"))
        // Emptied: confirmed as empty.
        assertEquals(EngineEdit(EngineCard.Codex, s, "", trimmed = false), EngineRows.review(view, EngineCard.Codex, s, "", "codex"))
        // Forced by the environment, or past the server's limit: nothing.
        val forced = ServerFixtures.view(envForced = mapOf("codexCommand" to true))
        assertNull(EngineRows.review(forced, EngineCard.Codex, s, "/opt/codex", "codex"))
        assertNull(EngineRows.review(view, EngineCard.Codex, s, "x".repeat(257), "codex"))
    }

    @Test fun aServerValueWithHiddenCharactersIsReplacedOnlyByAnEdit() {
        val spoofed = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "co\u202Edex")))
        // The field shows "codex" (hidden characters dropped); untouched, Done asks nothing.
        assertNull(EngineRows.review(spoofed, EngineCard.Codex, ServerSetting.CodexCommand, "codex", "codex"))
        // Edited, the clean value is offered (the confirmation shows the server's with its token).
        assertEquals("codex2", EngineRows.review(spoofed, EngineCard.Codex, ServerSetting.CodexCommand, "codex2", "codex")!!.value)
    }

    @Test fun useDetectedIsOfferedOnlyForAMissingFreeHome() {
        assertEquals(EngineEdit(EngineCard.Opencode, ServerSetting.OpencodeHome, "/home/op/.config/opencode", trimmed = false), EngineRows.useDetected(view, EngineCard.Opencode))
        // Claude's empty home is optional (issue #86): nothing to fill in.
        assertNull(EngineRows.useDetected(view, EngineCard.Claude))
        // Codex has a home; DeepSeek Harness has no detection.
        assertNull(EngineRows.useDetected(view, EngineCard.Codex))
        assertNull(EngineRows.useDetected(view, EngineCard.Dsh))
        assertNull(EngineRows.useDetected(ServerFixtures.view(envForced = mapOf("opencodeHome" to true)), EngineCard.Opencode))
    }

    @Test fun theConfirmationNamesWhatChangesAndWhatEmptyMeans() {
        val edit = EngineEdit(EngineCard.Claude, ServerSetting.ClaudeLaunchCommand, "w -- claude", trimmed = false)
        assertEquals("Change the Claude Code launch command?", EngineRows.confirmTitle(edit))
        assertEquals("Change launch command", EngineRows.confirmAction(edit))
        assertEquals("Change the Codex home?", EngineRows.confirmTitle(EngineEdit(EngineCard.Codex, ServerSetting.CodexHome, "", false)))
        assertEquals("Empty — your real HOME", EngineRows.emptyValue(EngineCard.Claude, ServerSetting.ClaudeHome))
        assertEquals("Empty — not set", EngineRows.emptyValue(EngineCard.Pi, ServerSetting.PiHome))
        assertEquals("Empty — runs “pi”", EngineRows.emptyValue(EngineCard.Pi, ServerSetting.PiCommand))
        assertEquals("Empty — no wrapper", EngineRows.emptyValue(EngineCard.Claude, ServerSetting.ClaudeLaunchCommand))
        assertEquals("Claude Code launch command", EngineRows.fieldLabel(EngineCard.Claude, ServerSetting.ClaudeLaunchCommand))
    }

    @Test fun theHomeCaptionFollowsTheWeb() {
        assertEquals("Optional — defaults to your real HOME", EngineRows.homeCaption(view, EngineCard.Claude))
        assertEquals("Dedicated credential home", EngineRows.homeCaption(view, EngineCard.Codex))
        assertEquals("Required — set a directory before enabling", EngineRows.homeCaption(view, EngineCard.Pi))
        assertEquals(ServerRowCopy.SET_BY_ENV, EngineRows.homeCaption(ServerFixtures.view(envForced = mapOf("piHome" to true)), EngineCard.Pi))
    }
}
