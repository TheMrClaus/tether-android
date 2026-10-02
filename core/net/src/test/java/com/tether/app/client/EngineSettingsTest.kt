package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ta-dh1: the Engines tab's reads (the tolerant, bounded `detected` decode, the headless-mode list)
 * and writes (the switch's `headlessModes` join with the issue #86 rule, and the confirmed
 * home / command / launch command value), plus `detect-engines` over a real socket.
 */
@OptIn(EngineConfirmationOnly::class)
class EngineSettingsTest {
    private fun frame(json: String) = ServerMessage.parse(json) as ServerMessage.ServerSettings

    private fun view(settings: String, envForced: String = "{}", detected: String = "{}") =
        ServerSettingsView.of(frame("""{"type":"server-settings","settings":$settings,"envForced":$envForced,"restartRequired":false,"discovered":[],"detected":$detected}"""))

    private fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject

    private fun encoded(patch: JsonObject?) = ClientMessage.SetServerSettings(patch!!).encode()

    /** A confirmed value built against the "Now" [v] itself holds (the confirmation showed the frame's own value). */
    private fun engineValueAsShown(v: ServerSettingsView, s: ServerSetting, value: String) =
        ServerSettingsPatch.engineValue(v, s, value, expectedNow = v.text(s))

    /**
     * ta-q9l r2 (security F1): a value confirmed against a "Now" the frame no longer holds builds
     * nothing; the same value against the frame's own "Now" builds (the positive control).
     */
    @Test fun aConfirmedValueBuildsOnlyAgainstTheNowItWasConfirmedOn() {
        val v = view("""{"codexCommand":"/opt/other"}""")
        assertNull(ServerSettingsPatch.engineValue(v, ServerSetting.CodexCommand, "/opt/codex", expectedNow = "codex"))
        assertNull(ServerSettingsPatch.engineValue(v, ServerSetting.CodexCommand, "/opt/codex", expectedNow = ""))
        assertEquals(json("""{"codexCommand":"/opt/codex"}"""), ServerSettingsPatch.engineValue(v, ServerSetting.CodexCommand, "/opt/codex", expectedNow = "/opt/other")?.patch)
    }

    // ---- the detection decode -------------------------------------------------------------------

    @Test fun theRecordedFrameReadsEachEnginesDetection() {
        val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire/settings.jsonl")
        val raw = wire.readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }
            .filter { it["dir"]?.jsonPrimitive?.content == "s2c" }.map { it["frame"]!!.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "server-settings" }
        val v = ServerSettingsView.of(ServerMessage.parse(raw) as ServerMessage.ServerSettings)
        val claude = v.detection(EngineCard.Claude)!!
        assertTrue(claude.found)
        assertEquals("2.1.284", claude.version)
        assertEquals("user PATH", claude.source)
        assertEquals("<HOME>/.claude", claude.configDir)
        assertEquals("0.159.0", v.detection(EngineCard.Codex)!!.version)
        // The recorded server detected no DeepSeek Harness: no entry, the card says "scanning…".
        assertNull(v.detection(EngineCard.Dsh))
        assertEquals(listOf("fake"), v.headlessModes)
        assertTrue(v.forced(ServerSetting.HeadlessModes))
        assertTrue(v.forced(ServerSetting.ShareHostConfig))
        assertEquals("claude", v.text(ServerSetting.ClaudeCommand))
        assertEquals("", v.text(ServerSetting.CodexHome))
    }

    @Test fun oddDetectionsReadAsTheWebReadsThem() {
        val long = "x".repeat(EngineDetection.MAX_TEXT + 1)
        val v = view(
            "{}",
            detected = """{
                "claude":{"found":1,"version":2,"source":7,"configDir":["/a"],"binPath":null},
                "codex":"yes",
                "opencode":false,
                "reasonix":[],
                "pi":{"found":"","version":null,"configDir":"$long","binPath":"$long"},
                "dsh":null,
                "zz":{"found":true}
            }""",
        )
        val claude = v.detection(EngineCard.Claude)!!
        assertTrue(claude.found) // truthy 1
        assertEquals("2", claude.version) // `${2}`
        assertNull(claude.source) // not a string: "not found" by the label switch
        assertNull(claude.configDir)
        assertEquals(EngineDetection(false, null, null, null, null), v.detection(EngineCard.Codex)) // truthy, not an object
        assertNull(v.detection(EngineCard.Opencode)) // falsy: no detection
        assertEquals(EngineDetection(false, null, null, null, null), v.detection(EngineCard.Reasonix))
        val pi = v.detection(EngineCard.Pi)!!
        assertFalse(pi.found)
        assertNull("an over-long path reads as absent", pi.configDir)
        assertNull(pi.binPath)
        assertNull(v.detection(EngineCard.Dsh))
        assertEquals("not found", EngineDetection.sourceLabel(claude.source))
        assertEquals("host install", EngineDetection.sourceLabel("host install"))
        assertEquals("not found", EngineDetection.sourceLabel("USER PATH"))
    }

    @Test fun aHugeDetectedMapIsReadOnlyForTheSixEngines() {
        val entries = (0 until 20_000).joinToString(",") { """"e$it":{"found":true}""" }
        val v = view("{}", detected = """{$entries,"codex":{"found":true,"version":"1"}}""")
        assertEquals("1", v.detection(EngineCard.Codex)!!.version)
        assertNull(v.detection(EngineCard.Claude))
    }

    @Test fun theHeadlessModeListIsTrimmedAsTheWebTrimsIt() {
        assertEquals(listOf("claude", "codex", "fake"), ServerSettingsView.headlessModesList(" claude, ,codex ,fake,"))
        // JavaScript's trim: U+FEFF and NBSP go, U+001F stays (Kotlin's trim would do the opposite).
        assertEquals(listOf("pi", "\u001Fdsh"), ServerSettingsView.headlessModesList("\uFEFFpi\u00A0,\u001Fdsh"))
        assertEquals(emptyList<String>(), ServerSettingsView.headlessModesList(""))
        assertEquals("a b", jsTrim(" \u2028\u3000 a b\t\r\n"))
    }

    // ---- the switch: headlessModes, joined as the web joins it ------------------------------------

    @Test fun aSwitchAddsOrRemovesItsEngineAndSendsTheJoinedList() {
        val v = view("""{"headlessModes":"claude, fake","codexHome":"/srv/codex","claudeHome":null}""")
        assertEquals(
            """{"type":"set-server-settings","settings":{"headlessModes":"claude,fake,codex"}}""",
            encoded(ServerSettingsPatch.headlessMode(v, EngineCard.Codex)),
        )
        assertEquals(
            """{"type":"set-server-settings","settings":{"headlessModes":"fake"}}""",
            encoded(ServerSettingsPatch.headlessMode(v, EngineCard.Claude)),
        )
        // Only the one key, never the homes.
        assertEquals(setOf("headlessModes"), ServerSettingsPatch.headlessMode(v, EngineCard.Codex)!!.keys)
    }

    @Test fun issue86ClaudesEmptyHomeNeverBlocksItsSwitchButTheOthersFailClosed() {
        val v = view("""{"headlessModes":"","claudeHome":"","codexHome":""}""")
        assertFalse(v.needsHome(EngineCard.Claude))
        assertEquals(json("""{"headlessModes":"claude"}"""), ServerSettingsPatch.headlessMode(v, EngineCard.Claude))
        assertTrue(v.needsHome(EngineCard.Codex))
        assertNull(ServerSettingsPatch.headlessMode(v, EngineCard.Codex))
        // A home the environment forces unblocks it (settings-dialog.tsx:2154 `noHome && !homeForced`).
        val forcedHome = view("""{"headlessModes":"","codexHome":""}""", envForced = """{"codexHome":true}""")
        assertEquals(json("""{"headlessModes":"codex"}"""), ServerSettingsPatch.headlessMode(forcedHome, EngineCard.Codex))
    }

    @Test fun forcedOrOversizeModesAreNeverWritten() {
        val forced = view("""{"headlessModes":"claude"}""", envForced = """{"headlessModes":true}""")
        for (e in EngineCard.entries) assertNull(ServerSettingsPatch.headlessMode(forced, e))
        val full = view("""{"headlessModes":"${"m".repeat(254)}","codexHome":"/c"}""")
        assertNull("the server would refuse a list past 256 bytes", ServerSettingsPatch.headlessMode(full, EngineCard.Codex))
    }

    // ---- the confirmed value: home, command, launch command ---------------------------------------

    @Test fun aConfirmedValueSendsOnlyItsKeyValuedAsTheWebsBlur() {
        val v = view("""{"claudeHome":"/home/op","claudeCommand":"claude","claudeLaunchCommand":"","codexHome":"/c","codexCommand":"codex"}""")
        assertEquals(
            """{"type":"set-server-settings","settings":{"claudeCommand":"/opt/claude/bin/claude"}}""",
            encoded(engineValueAsShown(v, ServerSetting.ClaudeCommand, "/opt/claude/bin/claude")?.patch),
        )
        // An emptied home or launch command is null (`value || null`); an emptied command is "".
        assertEquals(json("""{"claudeHome":null}"""), engineValueAsShown(v, ServerSetting.ClaudeHome, "")?.patch)
        assertEquals(json("""{"codexCommand":""}"""), engineValueAsShown(v, ServerSetting.CodexCommand, "")?.patch)
        assertEquals(json("""{"claudeLaunchCommand":"jean-claude run -- claude"}"""), engineValueAsShown(v, ServerSetting.ClaudeLaunchCommand, "jean-claude run -- claude")?.patch)
        // The server's value already: nothing.
        assertNull(engineValueAsShown(v, ServerSetting.ClaudeHome, "/home/op")?.patch)
        assertNull(engineValueAsShown(v, ServerSetting.ClaudeLaunchCommand, "")?.patch)
        // The value is sent exactly as confirmed (the caller trims before the confirmation).
        assertEquals(json("""{"codexHome":" /x "}"""), engineValueAsShown(v, ServerSetting.CodexHome, " /x ")?.patch)
    }

    @Test fun anEnvForcedOrOversizeValueIsNeverWritten() {
        val v = view("""{"claudeCommand":"claude","dshHome":"/d"}""", envForced = """{"claudeCommand":true}""")
        assertNull(engineValueAsShown(v, ServerSetting.ClaudeCommand, "other")?.patch)
        assertNull(engineValueAsShown(v, ServerSetting.DshCommand, "é".repeat(129))?.patch) // 258 bytes
        assertEquals(json("""{"dshCommand":"${"é".repeat(128)}"}"""), engineValueAsShown(v, ServerSetting.DshCommand, "é".repeat(128))?.patch)
        assertNull(engineValueAsShown(v, ServerSetting.DshHome, "/".repeat(4097))?.patch)
        // Only a value that sets what the server runs goes through here.
        assertNull(engineValueAsShown(v, ServerSetting.Host, "x")?.patch)
    }

    @Test fun everyUnconfirmedBuilderRefusesWhatTheServerRuns() {
        val v = view("""{"claudeCommand":"claude","codexHome":"/c","claudeLaunchCommand":""}""")
        val runs = ServerSetting.entries.filter { it.kind == SettingKind.Runs }
        assertEquals(13, runs.size) // 6 homes, 6 commands, the launch command
        for (s in runs) {
            assertNull(s.key, ServerSettingsPatch.text(v, s, "/tmp/evil", v.text(s)))
            assertNull(s.key, ServerSettingsPatch.choice(v, s, "/tmp/evil"))
            assertNull(s.key, ServerSettingsPatch.number(v, s, "7"))
            assertNull(s.key, ServerSettingsPatch.toggle(v, s))
            assertNull(s.key, ServerSettingsPatch.addPath(v, s, "/tmp/evil"))
            assertNull(s.key, ServerSettingsPatch.removePath(v, s, ""))
            // Only the confirmed write names it.
            assertEquals(setOf(s.key), engineValueAsShown(v, s, "/tmp/evil")!!.patch.keys)
        }
        assertEquals(runs.map { it.key }.toSet(), ServerSettingsPatch.runsKeys)
        assertTrue(ServerSettingsPatch.touchesWhatRuns(json("""{"host":"x","piHome":"/p"}""")))
        assertFalse(ServerSettingsPatch.touchesWhatRuns(json("""{"host":"x","headlessModes":"pi","shareHostConfig":true}""")))
        // A confirmed write prints its key only.
        assertEquals("ConfirmedEngineWrite([codexHome])", engineValueAsShown(v, ServerSetting.CodexHome, "/secret/path").toString())
    }

    @Test fun shareHostConfigFlipsLikeAnyToggleAndLocksWhenForced() {
        val v = view("""{"shareHostConfig":true}""")
        assertEquals(
            """{"type":"set-server-settings","settings":{"shareHostConfig":false}}""",
            encoded(ServerSettingsPatch.toggle(v, ServerSetting.ShareHostConfig)),
        )
        assertNull(ServerSettingsPatch.toggle(view("{}", envForced = """{"shareHostConfig":true}"""), ServerSetting.ShareHostConfig))
    }

    // ---- detect-engines over a socket -------------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    @Test fun scanAgainSendsDetectEnginesOnlyToItsServerAndEveryReplyIsCounted() {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        val origin = serverOrigin(h.server.url("/").toString())!!

        assertFalse(h.client.detectEngines("https://elsewhere.example"))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        assertTrue(h.client.detectEngines(origin))
        assertEquals(json("""{"type":"detect-engines"}"""), h.expectFrame("detect-engines"))

        val reply = """{"type":"server-settings","settings":{"headlessModes":"claude"},"envForced":{},"restartRequired":false,"discovered":[],"detected":{"claude":{"found":true}}}"""
        val before = h.client.serverSettingsReplies.value
        ws.send(reply)
        h.await(h.client.serverSettingsReplies) { it == before + 1 }
        // The same reply again: the frame flow does not change, the count does.
        ws.send(reply)
        h.await(h.client.serverSettingsReplies) { it == before + 2 }
        assertTrue(h.client.serverSettings.value!!.detected.containsKey("claude"))
    }

    @Test fun theClientSendsWhatTheServerRunsOnlyAsAConfirmedWrite() {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        val origin = serverOrigin(h.server.url("/").toString())!!
        // A plain patch naming an engine home, command or launch command never reaches the wire.
        assertFalse(h.client.setServerSettings(json("""{"codexCommand":"/tmp/evil"}"""), origin))
        assertFalse(h.client.setServerSettings(json("""{"host":"h","claudeLaunchCommand":"x"}"""), origin))
        val v = view("""{"codexCommand":"codex"}""")
        val write = engineValueAsShown(v, ServerSetting.CodexCommand, "/opt/codex")!!
        assertFalse("bound to its server", h.client.setConfirmedEngineValue(write, "https://elsewhere.example"))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        assertTrue(h.client.setConfirmedEngineValue(write, origin))
        assertEquals(json("""{"type":"set-server-settings","settings":{"codexCommand":"/opt/codex"}}"""), h.expectFrame("set-server-settings"))
        // Other keys still go as plain patches.
        assertTrue(h.client.setServerSettings(json("""{"headlessModes":"claude"}"""), origin))
        assertEquals(json("""{"type":"set-server-settings","settings":{"headlessModes":"claude"}}"""), h.expectFrame("set-server-settings"))
    }

    @Test fun noScanWithoutAHandshakenSocket() {
        h.newClient(configured = false)
        assertFalse(h.client.detectEngines("http://localhost"))
    }
}
