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
 * ta-t7l: the typed `server-settings` view (tolerant, the web's coercions), the exact
 * `set-server-settings` / `set-advanced-settings` patches (only the changed key), and the client's
 * advanced-settings route and origin-bound writes over a real socket.
 */
class ServerSettingsViewTest {
    private val sentinel = "SENTINEL-pw-41f0"
    private val tokenSentinel = "SENTINEL-proxy-c7d2"

    private fun frame(json: String) = ServerMessage.parse(json) as ServerMessage.ServerSettings

    private fun view(settings: String, envForced: String = "{}", restart: Boolean = false) =
        ServerSettingsView.of(frame("""{"type":"server-settings","settings":$settings,"envForced":$envForced,"restartRequired":$restart,"discovered":[],"detected":{}}"""))

    private fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject

    // ---- the view ------------------------------------------------------------------------------

    @Test fun theRecordedFrameReadsAsTheWebReadsIt() {
        val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire/settings.jsonl")
        val raw = wire.readLines().filter { it.isNotBlank() }.map { TetherJson.parseToJsonElement(it).jsonObject }
            .filter { it["dir"]?.jsonPrimitive?.content == "s2c" }.map { it["frame"]!!.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "server-settings" }
        val v = ServerSettingsView.of(ServerMessage.parse(raw) as ServerMessage.ServerSettings)
        assertEquals("127.0.0.1", v.text(ServerSetting.Host))
        assertEquals(0.0, v.number(ServerSetting.Port))
        assertEquals("verify-pass", v.secret(ServerSetting.Password).reveal())
        assertTrue(v.secret(ServerSetting.ProxyToken).isEmpty)
        assertEquals(8.0, v.number(ServerSetting.WarmMaxSessions))
        assertEquals("end", v.choice(ServerSetting.MessageInterruptMode))
        assertEquals("", v.choice(ServerSetting.DefaultSandboxPolicy))
        assertEquals(emptyList<String>(), v.paths(ServerSetting.AllowedRoots))
        assertTrue(v.toggle(ServerSetting.MetadataGenerationEnabled))
        assertEquals("automatic", v.choice(ServerSetting.MetadataGenerationMode))
        // The recorded env locks (true ones only), with stateDir always forced by the server.
        assertTrue(v.forced(ServerSetting.Host) && v.forced(ServerSetting.Password) && v.forced(ServerSetting.StateDir))
        assertFalse(v.forced(ServerSetting.ProxyToken))
        assertFalse(v.restartRequired)
    }

    @Test fun missingAndOddKeysReadAsTheWebsFallbacks() {
        val v = view("""{"host":7,"port":"4173","claudePersistent":"yes","archiveOnMerge":0,"allowedRoots":"/srv","spawnExtraWritableRoots":["/a",3,"/b"],"defaultSandboxPolicy":null,"future":{"x":1}}""")
        assertEquals("7", v.text(ServerSetting.Host)) // String(7)
        assertNull(v.number(ServerSetting.Port)) // typeof "4173" !== "number"
        assertTrue(v.toggle(ServerSetting.ClaudePersistent)) // Boolean("yes")
        assertFalse(v.toggle(ServerSetting.ArchiveOnMerge)) // Boolean(0)
        assertEquals(emptyList<String>(), v.paths(ServerSetting.AllowedRoots))
        assertEquals(listOf("/a", "/b"), v.paths(ServerSetting.SpawnExtraWritableRoots))
        assertEquals("", v.choice(ServerSetting.DefaultSandboxPolicy))
        // Absent everything: no throw, the empty value.
        val empty = view("{}")
        for (s in ServerSetting.entries) {
            empty.text(s); empty.number(s); empty.toggle(s); empty.choice(s); empty.paths(s); empty.secret(s)
            assertFalse(empty.forced(s))
        }
        assertEquals("", empty.secret(ServerSetting.Password).reveal())
    }

    @Test fun onlyATrueEnvFlagLocks() {
        val v = view("{}", envForced = """{"host":true,"port":false,"password":"true"}""")
        assertEquals(setOf("host"), v.envForced)
    }

    @Test fun noSecretIsEverPrinted() {
        val v = view("""{"password":"$sentinel","proxyToken":"$tokenSentinel","host":"h"}""")
        for (text in listOf(v.toString(), v.secret(ServerSetting.Password).toString(), v.secret(ServerSetting.ProxyToken).toString())) {
            assertFalse(text, text.contains(sentinel))
            assertFalse(text, text.contains(tokenSentinel))
        }
        assertEquals("SecretText(***)", v.secret(ServerSetting.Password).toString())
        // Two secrets compare by value (a re-sent frame keeps the reveal row as it was).
        assertEquals(v.secret(ServerSetting.Password), SecretText(sentinel))
    }

    // ---- the patches: exactly the changed key, valued as the web's row sends it ------------------

    @Test fun aTextRowSendsItsKeyOnlyWhenTheFieldChanged() {
        val v = view("""{"host":"0.0.0.0","workspaceRoot":"/srv"}""")
        assertNull(ServerSettingsPatch.text(v, ServerSetting.Host, "0.0.0.0", "0.0.0.0"))
        assertEquals(json("""{"host":"127.0.0.1"}"""), ServerSettingsPatch.text(v, ServerSetting.Host, "127.0.0.1", "0.0.0.0"))
        // `current || null`: an emptied field sends null.
        assertEquals(json("""{"workspaceRoot":null}"""), ServerSettingsPatch.text(v, ServerSetting.WorkspaceRoot, "", "/srv"))
        assertEquals(
            """{"type":"set-server-settings","settings":{"host":"127.0.0.1"}}""",
            ClientMessage.SetServerSettings(ServerSettingsPatch.text(v, ServerSetting.Host, "127.0.0.1", "0.0.0.0")!!).encode(),
        )
    }

    @Test fun aSecretRowSendsTheNewSecretUnderItsOwnKeyOnly() {
        val v = view("""{"password":"$sentinel","proxyToken":""}""")
        assertNull(ServerSettingsPatch.text(v, ServerSetting.Password, sentinel, sentinel))
        assertEquals(json("""{"password":"n3w"}"""), ServerSettingsPatch.text(v, ServerSetting.Password, "n3w", sentinel))
        assertEquals(json("""{"proxyToken":"tok"}"""), ServerSettingsPatch.text(v, ServerSetting.ProxyToken, "tok", ""))
    }

    @Test fun aNumberRowSendsTheParsedNumberOrNull() {
        val v = view("""{"port":4173,"warmMaxSessions":8,"maxConcurrentTurns":null}""")
        assertNull(ServerSettingsPatch.number(v, ServerSetting.Port, " 4173 "))
        assertEquals(json("""{"port":4174}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "4174"))
        assertEquals(json("""{"warmMaxSessions":null}"""), ServerSettingsPatch.number(v, ServerSetting.WarmMaxSessions, ""))
        assertNull(ServerSettingsPatch.number(v, ServerSetting.MaxConcurrentTurns, ""))
        assertEquals(json("""{"maxConcurrentTurns":0}"""), ServerSettingsPatch.number(v, ServerSetting.MaxConcurrentTurns, "0"))
    }

    /**
     * ta-coik.4: the browser's number input and `Number(text)` (settings-dialog.tsx:181-187), not the
     * retired "digits only" rule. Positive: exponent, sign, fraction and big numbers are sent as
     * `JSON.stringify` writes them. Negative: text the number input does not keep is its "" (null),
     * and an equal number (`===`, so -0 is 0) sends nothing.
     */
    @Test fun aNumberRowParsesAsTheBrowsersNumberInput() {
        val v = view("""{"port":4173,"warmMaxSessions":8,"maxConcurrentTurns":null,"warmSweepMs":1.5}""")
        assertEquals(json("""{"port":60000}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "6e4"))
        assertEquals(json("""{"port":60000}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "6E+4"))
        assertEquals(json("""{"port":-5}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "-5"))
        assertEquals(json("""{"port":41.5}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "41.5"))
        assertEquals(json("""{"port":0.5}"""), ServerSettingsPatch.number(v, ServerSetting.Port, ".5"))
        assertEquals(json("""{"port":100000000000000000000}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "99999999999999999999"))
        assertEquals(json("""{"port":1e+21}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "1e21"))
        assertEquals("JSON.stringify(Infinity) is null", json("""{"port":null}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "1e400"))
        assertEquals(json("""{"port":0}"""), ServerSettingsPatch.number(v, ServerSetting.Port, "-0"))
        // Not a valid floating-point number: the input's value is "", so the setting is cleared.
        for (bad in listOf("1e", "+5", "5.", "0x10", "1,5", "Infinity", "abc", "--1")) {
            assertEquals(bad, json("""{"port":null}"""), ServerSettingsPatch.number(v, ServerSetting.Port, bad))
            assertNull(bad, ServerSettingsPatch.number(v, ServerSetting.MaxConcurrentTurns, bad))
        }
        // `===`: the same number in another spelling sends nothing.
        assertNull(ServerSettingsPatch.number(v, ServerSetting.Port, "4.173e3"))
        assertNull(ServerSettingsPatch.number(v, ServerSetting.Port, "04173"))
        assertNull(ServerSettingsPatch.number(v, ServerSetting.WarmSweepMs, "1.50"))
        assertNull(ServerSettingsPatch.number(view("""{"port":0}"""), ServerSetting.Port, "-0"))
        // The field shows the server's number as String(n).
        assertEquals("1.5", v.numberText(ServerSetting.WarmSweepMs))
        assertEquals("4173", v.numberText(ServerSetting.Port))
        assertEquals("1e+21", view("""{"port":1e21}""").numberText(ServerSetting.Port))
        assertEquals("", v.numberText(ServerSetting.MaxConcurrentTurns))
    }

    @Test fun aToggleSendsTheFlippedValue() {
        val v = view("""{"claudePersistent":true}""")
        assertEquals(json("""{"claudePersistent":false}"""), ServerSettingsPatch.toggle(v, ServerSetting.ClaudePersistent))
        assertEquals(json("""{"archiveOnMerge":true}"""), ServerSettingsPatch.toggle(v, ServerSetting.ArchiveOnMerge))
    }

    @Test fun aSelectSendsTheNewOptionAndTheEmptyOptionAsNull() {
        val v = view("""{"defaultSandboxPolicy":"read-only","messageInterruptMode":"interrupt"}""")
        assertNull(ServerSettingsPatch.choice(v, ServerSetting.MessageInterruptMode, "interrupt"))
        assertEquals(json("""{"messageInterruptMode":"end"}"""), ServerSettingsPatch.choice(v, ServerSetting.MessageInterruptMode, "end"))
        assertEquals(json("""{"defaultSandboxPolicy":null}"""), ServerSettingsPatch.choice(v, ServerSetting.DefaultSandboxPolicy, ""))
    }

    @Test fun aRootsRowSendsTheWholeList() {
        val v = view("""{"allowedRoots":["/srv/a"]}""")
        assertEquals(json("""{"allowedRoots":["/srv/a","/srv/b"]}"""), ServerSettingsPatch.addPath(v, ServerSetting.AllowedRoots, "  /srv/b "))
        assertNull(ServerSettingsPatch.addPath(v, ServerSetting.AllowedRoots, "/srv/a"))
        assertNull(ServerSettingsPatch.addPath(v, ServerSetting.AllowedRoots, "   "))
        assertEquals(json("""{"allowedRoots":[]}"""), ServerSettingsPatch.removePath(v, ServerSetting.AllowedRoots, "/srv/a"))
        assertNull(ServerSettingsPatch.removePath(v, ServerSetting.AllowedRoots, "/nope"))
    }

    @Test fun anEnvForcedKeyIsNeverWritten() {
        val v = view(
            """{"host":"0.0.0.0","port":1,"claudePersistent":true,"messageInterruptMode":"end","allowedRoots":["/a"],"password":"p"}""",
            envForced = """{"host":true,"port":true,"claudePersistent":true,"messageInterruptMode":true,"allowedRoots":true,"password":true}""",
        )
        assertNull(ServerSettingsPatch.text(v, ServerSetting.Host, "x", "0.0.0.0"))
        assertNull(ServerSettingsPatch.text(v, ServerSetting.Password, "x", "p"))
        assertNull(ServerSettingsPatch.number(v, ServerSetting.Port, "2"))
        assertNull(ServerSettingsPatch.toggle(v, ServerSetting.ClaudePersistent))
        assertNull(ServerSettingsPatch.choice(v, ServerSetting.MessageInterruptMode, "interrupt"))
        assertNull(ServerSettingsPatch.addPath(v, ServerSetting.AllowedRoots, "/b"))
        assertNull(ServerSettingsPatch.removePath(v, ServerSetting.AllowedRoots, "/a"))
    }

    @Test fun theCliPickerSendsTheVersionOrNullAndNothingWhenForced() {
        val adv = ServerMessage.AdvancedSettings("2.1.0", emptyList(), false, null, "picker", "2.1.0")
        assertNull(ServerSettingsPatch.cliVersion(adv, "2.1.0"))
        assertEquals("""{"type":"set-advanced-settings","claudeCliVersion":null}""", ServerSettingsPatch.cliVersion(adv, "")!!.encode())
        assertEquals("""{"type":"set-advanced-settings","claudeCliVersion":"bundled"}""", ServerSettingsPatch.cliVersion(adv, "bundled")!!.encode())
        assertNull(ServerSettingsPatch.cliVersion(adv.copy(envForced = true, envPath = "/opt/claude"), "bundled"))
    }

    // ---- the client over a socket --------------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    @Test fun theClientRoutesAdvancedSettingsAndSendsOriginBoundWrites() {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        val origin = serverOrigin(h.server.url("/").toString())!!

        assertTrue(h.client.requestAdvancedSettings())
        h.expectFrame("advanced-settings")
        ws.send("""{"type":"advanced-settings","claudeCliVersion":null,"discovered":[{"version":"2.1.0"}],"envForced":false,"envPath":null,"effectiveSource":"bundled","effectiveVersion":"bundled"}""")
        h.await(h.client.advancedSettings) { it?.discovered?.singleOrNull()?.version == "2.1.0" }

        // A write drawn from ANOTHER server is not sent (nothing reaches the wire before the barrier).
        assertFalse(h.client.setServerSettings(json("""{"host":"x"}"""), "https://elsewhere.example"))
        assertFalse(h.client.setAdvancedSettings(ClientMessage.SetAdvancedSettings("bundled"), "https://elsewhere.example"))
        assertFalse("an empty patch is never sent", h.client.setServerSettings(JsonObject(emptyMap()), origin))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())

        // Its own server's writes go out exactly as built.
        assertTrue(h.client.setServerSettings(json("""{"warmSweepMs":30000}"""), origin))
        assertEquals(json("""{"type":"set-server-settings","settings":{"warmSweepMs":30000}}"""), h.expectFrame("set-server-settings"))
        assertTrue(h.client.setAdvancedSettings(ClientMessage.SetAdvancedSettings("2.1.0"), origin))
        assertEquals(json("""{"type":"set-advanced-settings","claudeCliVersion":"2.1.0"}"""), h.expectFrame("set-advanced-settings"))

        // The reply carries restartRequired: the banner's source (settings-dialog.tsx:1998).
        ws.send("""{"type":"server-settings","settings":{"warmSweepMs":30000},"envForced":{},"restartRequired":true,"discovered":[],"detected":{}}""")
        h.await(h.client.serverSettings) { it?.restartRequired == true }
    }

    private val settingsFrame =
        """{"type":"server-settings","settings":{"password":"SENTINEL-pw-41f0"},"envForced":{},"restartRequired":false,"discovered":[],"detected":{}}"""
    private val advancedFrame =
        """{"type":"advanced-settings","claudeCliVersion":null,"discovered":[],"envForced":false,"envPath":null,"effectiveSource":"bundled","effectiveVersion":"bundled"}"""

    /** Connected, with both settings frames held. */
    private fun holdingSettings(): okhttp3.WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        ws.send(settingsFrame)
        ws.send(advancedFrame)
        h.await(h.client.serverSettings) { it != null }
        h.await(h.client.advancedSettings) { it != null }
        return ws
    }

    /** r2: a sign-out drops both frames (the plaintext password goes with them). */
    @Test fun aSignOutDropsTheSettingsFrames() {
        holdingSettings()
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}"))
        kotlinx.coroutines.runBlocking { h.client.logout() }
        assertNull(h.client.serverSettings.value)
        assertNull(h.client.advancedSettings.value)
    }

    /** r2: a server-side revocation (close 4001 -> auth required) drops them too. */
    @Test fun authRequiredDropsTheSettingsFrames() {
        val ws = holdingSettings()
        ws.close(4001, "device revoked")
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        assertNull(h.client.serverSettings.value)
        assertNull(h.client.advancedSettings.value)
    }

    /** r2: `text()` never hands out a secret; only `secret()` does. */
    @Test fun textNeverReadsASecret() {
        val v = view("""{"password":"$sentinel","proxyToken":"$tokenSentinel"}""")
        assertEquals("", v.text(ServerSetting.Password))
        assertEquals("", v.text(ServerSetting.ProxyToken))
        assertEquals("", v.choice(ServerSetting.Password))
        assertEquals(sentinel, v.secret(ServerSetting.Password).reveal())
        // The secret rows' patch still sends the edited secret (built from the revealed value).
        assertEquals(json("""{"password":"x"}"""), ServerSettingsPatch.text(v, ServerSetting.Password, "x", v.secret(ServerSetting.Password).reveal()))
    }

    @Test fun nothingIsSentWithoutAHandshakenSocket() {
        h.newClient(configured = false)
        assertFalse(h.client.requestAdvancedSettings())
        assertFalse(h.client.setServerSettings(json("""{"host":"x"}"""), "http://localhost"))
    }

    @Test fun clearDropsTheAdvancedSettingsWithTheRest() {
        val sync = SidebarSync()
        assertTrue(sync.onFrame(ServerMessage.AdvancedSettings(null, emptyList(), false, null, "", null)))
        sync.clear()
        assertNull(sync.advancedSettings.value)
    }
}
