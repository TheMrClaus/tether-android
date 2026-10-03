package com.tether.app.ui.settings

import com.tether.app.client.EngineCard
import com.tether.app.client.EngineDetection
import com.tether.app.client.ServerSetting
import com.tether.app.client.ServerSettingsPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ta-dh1: the Engines tab's words and its pure rules (what a field writes, "Use detected"; ta-coik.5: no confirmation). */
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

    /** settings-dialog.tsx 90fbb9f :2209 (the command's blur): the trimmed value, when it differs; no confirmation. */
    @Test fun aFieldWritesWhatItsBlurWouldOnTheWeb() {
        val s = ServerSetting.CodexCommand
        // Untouched, or only spaces around the server's value: nothing to send.
        assertNull(ServerSettingsPatch.engineValue(view, s, "codex", "codex"))
        assertNull(ServerSettingsPatch.engineValue(view, s, "  codex ", "codex"))
        assertEquals(ServerFixtures.json("""{"codexCommand":"/opt/codex"}"""), ServerSettingsPatch.engineValue(view, s, "/opt/codex", "codex"))
        assertEquals(ServerFixtures.json("""{"codexCommand":"/opt/codex"}"""), ServerSettingsPatch.engineValue(view, s, " /opt/codex\t", "codex"))
        // A command emptied is sent empty (the server runs the engine's own name); a home or launch command as null.
        assertEquals(ServerFixtures.json("""{"codexCommand":""}"""), ServerSettingsPatch.engineValue(view, s, "", "codex"))
        // Forced by the environment (the web's field is disabled), or past the server's limit: nothing.
        val forced = ServerFixtures.view(envForced = mapOf("codexCommand" to true))
        assertNull(ServerSettingsPatch.engineValue(forced, s, "/opt/codex", "codex"))
        assertNull(ServerSettingsPatch.engineValue(view, s, "x".repeat(257), "codex"))
    }

    @Test fun aServerValueWithHiddenCharactersIsReplacedOnlyByAnEdit() {
        val spoofed = ServerFixtures.view(ServerFixtures.settingsJson(overrides = mapOf("codexCommand" to "co\u202Edex")))
        // The field shows "codex" (hidden characters dropped); untouched, nothing is sent.
        assertNull(ServerSettingsPatch.engineValue(spoofed, ServerSetting.CodexCommand, "codex", "codex"))
        assertEquals(ServerFixtures.json("""{"codexCommand":"codex2"}"""), ServerSettingsPatch.engineValue(spoofed, ServerSetting.CodexCommand, "codex2", "codex"))
    }

    /** :2191 `noHome && det?.configDir` (ta-coik.5: with no env check, as on the web). */
    @Test fun useDetectedIsOfferedWhereTheWebOffersIt() {
        assertEquals("/home/op/.config/opencode", ServerSettingsPatch.detectedHome(view, EngineCard.Opencode))
        assertEquals(ServerFixtures.json("""{"opencodeHome":"/home/op/.config/opencode"}"""), ServerSettingsPatch.useDetected(view, EngineCard.Opencode))
        // Claude's empty home is optional (issue #86): nothing to fill in.
        assertNull(ServerSettingsPatch.detectedHome(view, EngineCard.Claude))
        // Codex has a home; DeepSeek Harness has no detection.
        assertNull(ServerSettingsPatch.detectedHome(view, EngineCard.Codex))
        assertNull(ServerSettingsPatch.detectedHome(view, EngineCard.Dsh))
        // Env-forced: still offered, as on the web (the server decides).
        val forced = ServerFixtures.view(envForced = mapOf("opencodeHome" to true))
        assertEquals("/home/op/.config/opencode", ServerSettingsPatch.detectedHome(forced, EngineCard.Opencode))
        assertEquals(ServerFixtures.json("""{"opencodeHome":"/home/op/.config/opencode"}"""), ServerSettingsPatch.useDetected(forced, EngineCard.Opencode))
    }

    @Test fun aFieldIsNamedForWhatItSets() {
        assertEquals("Claude Code launch command", EngineRows.fieldLabel(EngineCard.Claude, ServerSetting.ClaudeLaunchCommand))
    }

    @Test fun theHomeCaptionFollowsTheWeb() {
        assertEquals("Optional — defaults to your real HOME", EngineRows.homeCaption(view, EngineCard.Claude))
        assertEquals("Dedicated credential home", EngineRows.homeCaption(view, EngineCard.Codex))
        assertEquals("Required — set a directory before enabling", EngineRows.homeCaption(view, EngineCard.Pi))
        assertEquals(ServerRowCopy.SET_BY_ENV, EngineRows.homeCaption(ServerFixtures.view(envForced = mapOf("piHome" to true)), EngineCard.Pi))
    }
}
