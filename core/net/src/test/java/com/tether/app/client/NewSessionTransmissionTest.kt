package com.tether.app.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-895: the one place a New session row reaches the wire (RealTetherClient over a MockWebServer
 * socket). `create` carries the row's `profileId` (use-draft-composer.ts:339) only when the row is
 * one of the catalog THIS socket delivered, exactly as drawn; a catalog of an earlier connection is
 * never trusted, a row the refreshed catalog dropped or changed is refused (never created on the
 * default profile instead), and nothing received ever produces a create.
 */
class NewSessionTransmissionTest {

    private val h = ConnectionHarness()

    @After
    fun tearDown() = h.close()

    private val providers =
        """{"id":"claude","label":"Claude","glyph":"C","available":true},""" +
            """{"id":"codex","label":"Codex","glyph":"X","available":true},""" +
            """{"id":"acp","label":"ACP","glyph":"A","available":true}"""

    private fun ready(): String =
        """{"type":"ready","protocolVersion":${com.tether.app.protocol.PROTOCOL_VERSION},"nativeProtocolFloor":129,"sessions":[],""" +
            """"providers":[$providers],"workspaceRoot":null}"""

    private fun catalogFrame(vararg rows: String) = """{"type":"providers-snapshot","entries":[${rows.joinToString(",")}]}"""

    private val work = """{"key":"work","provider":"claude","status":"ready","profileId":"work","extends":"claude","label":"Claude (work)","models":[]}"""
    private val personal = """{"key":"personal","provider":"claude","status":"ready","profileId":"personal","extends":"claude","label":"Claude (personal)","models":[]}"""
    private val claude = """{"key":"claude","provider":"claude","status":"ready","label":"Claude","models":[]}"""
    private val codex = """{"key":"codex","provider":"codex","status":"ready","label":"Codex","models":[]}"""

    private val workChoice = NewSessionChoice("work", "claude", "work")
    private val claudeChoice = NewSessionChoice("claude", "claude", null)

    private fun connected(): Pair<RealTetherClient, WebSocket> {
        val client = h.newClient()
        h.enqueueConnect()
        client.start()
        val ws = h.nextSocket()
        h.handshake(ws, ready())
        return client to ws
    }

    private fun withCatalog(vararg rows: String = arrayOf(work, personal, claude, codex)): Pair<RealTetherClient, WebSocket> {
        val (client, ws) = connected()
        assertFalse("no catalog of this socket yet", client.providerCatalogLive.value)
        assertTrue(client.requestProviderCatalog())
        assertEquals(listOf("providers-snapshot"), h.framesUntilBarrier().map { it.type() })
        ws.send(catalogFrame(*rows))
        h.await(client.providerCatalogLive) { it }
        return client to ws
    }

    private fun creates(): List<JsonObject> = h.framesUntilBarrier().filter { it.type() == "create" }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun aProfileRowCreatesOnThatProfile() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.Sent, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        val sent = creates().single()
        assertEquals(setOf("type", "provider", "cwd", "profileId"), sent.keys)
        assertEquals("claude", sent.str("provider"))
        assertEquals("work", sent.str("profileId"))
        assertEquals("/w", sent.str("cwd"))
    }

    @Test
    fun aDefaultRowCreatesWithNoProfile() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.Sent, client.createNewSession(claudeChoice, "/w", client.consentOrigin.value))
        assertEquals(setOf("type", "provider", "cwd"), creates().single().keys)
    }

    @Test
    fun aRowTheRefreshedCatalogDroppedIsRefusedAndNothingIsCreatedOnTheDefault() {
        val (client, ws) = withCatalog()
        // The account was removed: the server pushes the catalog without it.
        ws.send(catalogFrame(personal, claude, codex))
        h.await(client.providerCatalog) { list -> list.none { it.key == "work" } }
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun aKeyThatNowNamesAnotherProfileIsRefused() {
        val (client, ws) = withCatalog()
        ws.send(catalogFrame("""{"key":"work","provider":"claude","status":"ready","profileId":"work-2","models":[]}""", claude))
        h.await(client.providerCatalog) { list -> list.any { it.profileId == "work-2" } }
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun anEarlierConnectionsCatalogIsNeverTrustedForAProfile() {
        val (client, ws) = withCatalog()
        h.enqueueConnect()
        ws.close(1001, null)
        h.await(client.connection) { it == ConnectionState.Disconnected }
        assertFalse(client.providerCatalogLive.value)
        assertEquals(NewSessionResult.NotConnected, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2, ready())
        // The old catalog is still published (the @ Agents keep it) but it is not this socket's.
        assertTrue(client.providerCatalog.value.any { it.key == "work" })
        assertFalse(client.providerCatalogLive.value)
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        // A base provider's default row still starts a session meanwhile, with no profile.
        assertEquals(NewSessionResult.Sent, client.createNewSession(claudeChoice, "/w", client.consentOrigin.value))
        val meanwhile = creates()
        assertEquals(1, meanwhile.size)
        assertEquals(setOf("type", "provider", "cwd"), meanwhile[0].keys)
        // This socket's own catalog lands: the profile row creates again.
        ws2.send(catalogFrame(work, claude))
        h.await(client.providerCatalogLive) { it }
        assertEquals(NewSessionResult.Sent, client.createNewSession(workChoice, "/w", client.consentOrigin.value))
        assertEquals("work", creates().single().str("profileId"))
    }

    @Test
    fun aRowDrawnForAnotherServerOrForNoneIsNotLive() {
        val (client, _) = withCatalog()
        assertEquals(NewSessionResult.NotLive, client.createNewSession(workChoice, "/w", "https://other.example"))
        assertEquals(NewSessionResult.NotLive, client.createNewSession(workChoice, "/w", null))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun anUnavailableOrLoadingRowIsRefusedAndAnAcpDefaultNeverExists() {
        val (client, _) = withCatalog(
            """{"key":"work","provider":"claude","status":"loading","profileId":"work","models":[]}""",
            """{"key":"codex","provider":"codex","status":"unavailable","models":[]}""",
            claude,
        )
        val origin = client.consentOrigin.value
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(workChoice, "/w", origin))
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(NewSessionChoice("codex", "codex", null), "/w", origin))
        assertEquals(NewSessionResult.NotOffered, client.createNewSession(NewSessionChoice("acp", "acp", null), "/w", origin))
        assertTrue(creates().isEmpty())
    }

    @Test
    fun nothingReceivedEverProducesACreate() {
        val (client, ws) = withCatalog()
        ws.send(catalogFrame(work, claude))
        ws.send("""{"type":"create","provider":"claude","profileId":"work","cwd":"/"}""")
        h.serverBarrier(ws)
        assertTrue(client.providerCatalogLive.value)
        assertTrue(creates().isEmpty())
    }
}
