package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-t7l: `server-settings` / `advanced-settings` decode tolerantly (a missing or wrongly typed
 * part never drops the frame into [ServerMessage.Unknown], whose `raw` would hold the secrets),
 * and neither settings frame prints a setting value.
 */
class SettingsFramesTest {
    private val sentinel = "SENTINEL-pw-7c1e"
    private val tokenSentinel = "SENTINEL-proxy-93ad"

    private fun parse(text: String) = ServerMessage.parse(text)

    @Test fun aFullServerSettingsFrameDecodes() {
        val m = parse(
            """{"type":"server-settings","settings":{"host":"0.0.0.0","password":"$sentinel"},"envForced":{"host":true,"port":"yes"},""" +
                """"restartRequired":true,"discovered":[{"version":"2.1.0"},{"bad":1}],"detected":{"claude":{}},"future":1}""",
        ) as ServerMessage.ServerSettings
        assertEquals(JsonPrimitive(sentinel), m.settings["password"])
        // A non-boolean envForced value is dropped; a malformed discovered entry is dropped.
        assertEquals(mapOf("host" to true), m.envForced)
        assertTrue(m.restartRequired)
        assertEquals(listOf(ClaudeCliVersion("2.1.0")), m.discovered)
        assertEquals(setOf("claude"), m.detected.keys)
    }

    @Test fun everyMissingOrWronglyTypedPartIsEmptyNotADroppedFrame() {
        val bare = parse("""{"type":"server-settings"}""") as ServerMessage.ServerSettings
        assertEquals(JsonObject(emptyMap()), bare.settings)
        assertEquals(emptyMap<String, Boolean>(), bare.envForced)
        assertFalse(bare.restartRequired)
        assertEquals(emptyList<ClaudeCliVersion>(), bare.discovered)
        assertEquals(JsonObject(emptyMap()), bare.detected)

        val odd = parse(
            """{"type":"server-settings","settings":"$sentinel","envForced":[1],"restartRequired":"true","discovered":{},"detected":7}""",
        )
        assertTrue("$odd", odd is ServerMessage.ServerSettings)
        assertFalse(odd.toString().contains(sentinel))
        assertFalse((odd as ServerMessage.ServerSettings).restartRequired)
    }

    @Test fun advancedSettingsDecodeTolerantly() {
        val full = parse(
            """{"type":"advanced-settings","claudeCliVersion":"2.1.0","discovered":[{"version":"2.1.0"}],"envForced":false,""" +
                """"envPath":null,"effectiveSource":"picker","effectiveVersion":"2.1.0"}""",
        ) as ServerMessage.AdvancedSettings
        assertEquals("2.1.0", full.claudeCliVersion)
        assertEquals("picker", full.effectiveSource)
        val bare = parse("""{"type":"advanced-settings"}""") as ServerMessage.AdvancedSettings
        assertEquals(ServerMessage.AdvancedSettings(null, emptyList(), false, null, "", null), bare)
    }

    @Test fun neitherSettingsFramePrintsASecret() {
        val settings = buildJsonObject {
            put("password", sentinel)
            put("proxyToken", tokenSentinel)
            put("host", "127.0.0.1")
        }
        val inbound = ServerMessage.ServerSettings(settings, mapOf("password" to false), false, emptyList(), JsonObject(emptyMap()))
        val outbound = ClientMessage.SetServerSettings(settings)
        for (text in listOf(inbound.toString(), outbound.toString())) {
            assertFalse(text, text.contains(sentinel))
            assertFalse(text, text.contains(tokenSentinel))
            assertTrue(text, text.contains("password"))
        }
        assertEquals("SetServerSettings(settings=[host, password, proxyToken])", outbound.toString())
        // The wire form still carries the value: only what is printed is redacted.
        assertTrue(outbound.encode().contains(sentinel))
    }
}
