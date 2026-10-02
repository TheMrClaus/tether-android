package com.tether.app.client

import com.tether.app.protocol.ClientMessage
import com.tether.app.protocol.SessionModelOption
import com.tether.app.protocol.model.ProviderInfo
import com.tether.app.protocol.tree.JsArr
import com.tether.app.protocol.tree.JsNum
import com.tether.app.protocol.tree.JsObj
import com.tether.app.protocol.tree.JsStr
import com.tether.app.ui.StubClient
import com.tether.app.ui.prefs.InMemoryDraftStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-2uq (T8.1 slice 3): the draft engine under the model browser: a pick sets the row (provider /
 * profile) and model together and only a pick made in this draft rides the create; custom model ids
 * follow the web's rule ([CustomModelId]: trimmed, not empty), are kept per server origin and ride
 * the create once picked. ta-coik.4: models are picked and sent as they are (the server validates).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftComposerBrowserTest {

    private companion object {
        const val A = "https://a.example:443"
        const val B = "https://b.example:443"
    }

    private class Client : StubClient() {
        override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        override val consentOrigin = MutableStateFlow<String?>(A)
        override val linkEpoch = MutableStateFlow(1L)
        override val providers = MutableStateFlow(listOf(ProviderInfo("claude", "Claude", "C", true)))
        override val providerCatalog = MutableStateFlow<List<ProviderCatalogEntry>>(emptyList())
        override val providerCatalogLive = MutableStateFlow(false)
        override val workspaceRoot = MutableStateFlow<String?>("/root")
        override val createdSessions = MutableStateFlow<CreatedReply?>(null)
        override val createErrors = MutableStateFlow<CreateErrorReply?>(null)
        val frames = mutableListOf<ClientMessage.Create>()

        override fun createNewSession(request: NewSessionRequest, expectedOrigin: String?): NewSessionResult {
            if (expectedOrigin != consentOrigin.value) return NewSessionResult.NotLive
            val frame = NewSessionGuard.resolve(request, if (providerCatalogLive.value) providerCatalog.value else null, providers.value)
                ?: return NewSessionResult.NotOffered
            frames += frame
            return NewSessionResult.Sent
        }

        fun live(vararg entries: ProviderCatalogEntry) {
            providerCatalog.value = entries.toList()
            providerCatalogLive.value = true
        }
    }

    private fun models(vararg ids: String) = ids.map { SessionModelOption(it, it.uppercase()) }

    private val workRow = ProviderCatalogEntry("work", "claude", "ready", models("m1", "m2"), label = "Claude (work)", profileId = "work", extends = "claude")
    private val claudeRow = ProviderCatalogEntry("claude", "claude", "ready", models("m1"), label = "Claude")

    private fun TestScope.engine(client: Client = Client(), store: InMemoryDraftStore = InMemoryDraftStore()): DraftComposerModel {
        val model = DraftComposerModel(client, store, backgroundScope, currentWorkspace = { "/w" }, newRequestId = { "req" })
        model.onOrigin(A)
        runCurrent()
        return model
    }

    private fun DraftComposerModel.form(key: String) = (state.value.form[key] as? JsStr)?.value.orEmpty()

    @Test
    fun aBrowserPickSetsTheRowAndTheModelAndOnlyAPickRidesTheCreate() = runTest {
        val client = Client().apply { live(workRow, claudeRow) }
        val model = engine(client)
        model.selectProviderAndModel("work", "m2")
        assertEquals("work", model.form("key"))
        assertEquals("m2", model.form("model"))
        model.setText("go")
        assertEquals(DraftSubmitResult.Sent, model.submit(A))
        assertEquals("work", client.frames.single().profileId)
        assertEquals("m2", client.frames.single().model)
        // Control: a row picked with no model pick sends no model (the display default is never pinned).
        val other = Client().apply { live(workRow, claudeRow) }
        val cold = engine(other)
        cold.selectProvider("claude")
        cold.setText("go")
        cold.submit(A)
        assertNull(other.frames.single().model)
    }

    /** ta-coik.4: a model past the retired 200-byte bound is picked and sent, as on the web (the server validates). */
    @Test
    fun aModelPastTheRetiredBoundIsPickedAndSent() = runTest {
        val long = "m" + "é".repeat(100) // 201 UTF-8 bytes
        val client = Client().apply { live(ProviderCatalogEntry("claude", "claude", "ready", models("m1") + SessionModelOption(long, "Long"))) }
        val model = engine(client)
        model.selectProviderAndModel("claude", long)
        assertEquals("claude", model.form("key"))
        assertEquals(long, model.form("model"))
        model.selectModel("m1")
        assertEquals("m1", model.form("model"))
        model.selectModel(long)
        assertEquals(long, model.form("model"))
        model.setText("go")
        assertEquals(DraftSubmitResult.Sent, model.submit(A))
        assertEquals(long, client.frames.single().model)
    }

    @Test
    fun customIdsFollowTheWebsRuleKeptPerServerAndRideTheCreate() = runTest {
        val client = Client().apply { live(workRow, claudeRow) }
        val model = engine(client)
        assertTrue(model.addCustomModel("claude", "  my-model[1m]  "))
        runCurrent()
        assertEquals(mapOf("claude" to listOf("my-model[1m]")), model.state.value.customModels)
        assertTrue(model.state.value.entries.first { it.key == "claude" }.models.any { it.value == "my-model[1m]" })
        // Refused, as the web disables its +: blank, a model the row already offers, the same id again.
        for (bad in listOf("   ", "m1", "my-model[1m]", " my-model[1m] ")) {
            assertFalse("refused: $bad", model.addCustomModel("claude", bad))
        }
        assertFalse("a row the browser does not list", model.addCustomModel("nope", "fine-id"))
        // ta-coik.4: taken, as on the web: two words, a bidi override, a zero-width space, 201 bytes.
        for (ok in listOf("a b", "x\u202Ey", "\u200Bx", "é".repeat(100) + "x")) {
            assertTrue("taken: $ok", model.addCustomModel("claude", ok))
            model.removeCustomModel("claude", ok)
        }
        assertEquals(listOf("my-model[1m]"), model.state.value.customModels["claude"])
        // Picked, it rides the create like any model.
        model.selectProviderAndModel("claude", "my-model[1m]")
        model.setText("go")
        model.submit(A)
        assertEquals("my-model[1m]", client.frames.single().model)
        runCurrent()
        // Another server never sees it; coming back reads it again from that server's record.
        model.onOrigin(B)
        runCurrent()
        assertTrue(model.state.value.customModels.isEmpty())
        assertTrue(model.state.value.entries.flatMap { it.models }.none { it.value == "my-model[1m]" })
        model.onOrigin(A)
        runCurrent()
        assertEquals(mapOf("claude" to listOf("my-model[1m]")), model.state.value.customModels)
    }

    /** ta-coik.4: stored ids read as the web reads them (strings, trimmed, empty and repeated skipped), no app filter. */
    @Test
    fun storedCustomIdsAreReadAsTheWebReadsThem() = runTest {
        val store = InMemoryDraftStore()
        store.writeDraftPreferences(
            A,
            JsObj.of(
                "customModels" to JsObj.of(
                    "claude" to JsArr.of(JsStr("ok-1"), JsStr("bad\u202Eid"), JsStr("has space"), JsStr("x".repeat(300)), JsNum(5.0), JsStr("ok-1"), JsStr("  ")),
                    "codex" to JsStr("not-a-list"),
                ),
            ),
        )
        val client = Client().apply { live(claudeRow) }
        val model = engine(client, store)
        val ids = listOf("ok-1", "bad\u202Eid", "has space", "x".repeat(300))
        assertEquals(mapOf("claude" to ids), model.state.value.customModels)
        assertEquals(listOf("m1") + ids, model.state.value.entries.single().models.map { it.value })
        // Picked, the long one rides the create as it is.
        model.selectProviderAndModel("claude", "x".repeat(300))
        model.setText("go")
        assertEquals(DraftSubmitResult.Sent, model.submit(A))
        assertEquals("x".repeat(300), client.frames.single().model)
    }
}
