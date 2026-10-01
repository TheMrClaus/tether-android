package com.tether.app.client

import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ta-q6p: the custom-providers registry (settings-dialog.tsx 887c222 `ProfilesEditor`): the tolerant,
 * capped read; each edit as the exact whole list the web would send; every write built from the
 * newest list (a concurrent broadcast is never overwritten); and the one rule every send path
 * applies: no write changes a profile's command or home unless it is that change's confirmation.
 */
class ProfilesTest {
    private val sentinel = "SENTINEL-env-81d0-do-not-leak"

    private fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject

    private fun frame(text: String) = ServerMessage.parse(text) as ServerMessage.Providers

    private fun list(profiles: String, generation: Long = 1) = ProvidersList.of(frame("""{"type":"providers","profiles":$profiles}"""), generation)

    private val gemini = """{"id":"gemini","extends":"acp","label":"Gemini","command":["gemini","--acp"],"homeDir":"/srv/homes/gemini",""" +
        """"env":{"GEMINI_API_KEY":"$sentinel","MODE":"x"},"dropEnv":["GEMINI"],"enabled":true,"order":2,"verifiedThrough":"0.43.0"}"""
    private val work = """{"id":"work","extends":"claude","label":"Work","homeDir":"/srv/homes/work","models":[{"id":"opus","isDefault":true},{"id":"sonnet","label":"Sonnet"}],"enabled":false}"""
    private val two = list("[$gemini,$work]")

    private fun sent(write: ProvidersWrite?): JsonObject = json(write!!.message.encode())

    private fun profiles(vararg p: String) = json("""{"type":"set-providers","profiles":[${p.joinToString(",")}]}""")

    // ---- the read ----------------------------------------------------------------------------------

    @Test fun theRecordedListReadsAndIsWritable() {
        val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire/settings.jsonl")
        val last = wire.readLines().filter { it.contains("\"type\":\"providers\",\"profiles\":[{") }.last()
        val l = ProvidersList.of(ServerMessage.parse(json(last)["frame"]!!.jsonObject) as ServerMessage.Providers, 1)
        assertTrue(l.writable)
        val p = l.profiles.single()
        assertEquals(listOf("parity-claude", "claude", "Parity Claude", true), listOf(p.id, p.extends, p.label, p.enabled))
    }

    @Test fun aProfileReadsAsTheWebReadsIt() {
        val p = two.profile("gemini")!!
        assertEquals(listOf("gemini", "--acp"), p.command)
        assertEquals("/srv/homes/gemini", p.homeDir)
        assertEquals(listOf("GEMINI_API_KEY", "MODE"), p.envKeys)
        assertEquals(sentinel, p.envValue("GEMINI_API_KEY")!!.reveal())
        assertEquals(listOf("GEMINI"), p.dropEnv)
        assertEquals(2L, p.order)
        assertEquals("0.43.0", p.verifiedThrough)
        val w = two.profile("work")!!
        assertEquals(listOf(ProfileModel("opus", null, true), ProfileModel("sonnet", "Sonnet", false)), w.models)
        assertTrue(two.writable)
    }

    @Test fun nothingPrintsAnEnvValue() {
        val p = two.profile("gemini")!!
        val write = ProvidersPatch.write(two, ProfileEdit.EnvValue("gemini", "MODE", SecretText(sentinel)))!!
        for (text in listOf(p.toString(), two.toString(), write.toString(), ProfileEdit.EnvValue("gemini", "MODE", SecretText(sentinel)).toString(),
            ProfileEdit.EnvAdd("gemini", "K", SecretText(sentinel)).toString(), p.envValue("GEMINI_API_KEY").toString())) {
            assertFalse(text, text.contains(sentinel))
        }
        assertTrue(p.toString().contains("GEMINI_API_KEY"))
    }

    @Test fun aHostileListIsReadCappedAndNeverWritable() {
        // Wrong types everywhere: read as absent, the frame and its other entries survive.
        val odd = list("""[{"id":7,"extends":"nope","label":["x"],"command":"gemini","homeDir":{},"env":["$sentinel"],"dropEnv":"A","models":[1,{"id":2}],"enabled":"yes","order":"3"}]""")
        val p = odd.profiles.single()
        assertEquals(listOf("", "", "", false), listOf(p.id, p.extends, p.label, p.enabled))
        assertNull(p.command)
        assertNull(p.homeDir)
        assertEquals(emptyList<String>(), p.envKeys)
        assertEquals(emptyList<ProfileModel>(), p.models)
        assertNull(p.order)
        assertFalse(odd.writable)
        assertNull("nothing is written from it", ProvidersPatch.write(odd, ProfileEdit.Add))
        // Past the server's limits: a 5000-unit label, 100 env keys, 40 command parts, 20000 profiles.
        val long = list("""[{"id":"a","extends":"codex","label":"${"x".repeat(5000)}","enabled":true}]""")
        assertEquals("", long.profiles.single().label)
        assertFalse(long.writable)
        val env = (1..100).joinToString(",") { "\"K$it\":\"v\"" }
        val many = list("""[{"id":"a","extends":"codex","label":"A","enabled":true,"env":{$env},"command":[${(1..40).joinToString(",") { "\"p\"" }}]}]""")
        assertEquals(ProfileLimits.ENV_KEYS, many.profiles.single().envKeys.size)
        assertEquals(ProfileLimits.COMMAND, many.profiles.single().command!!.size)
        assertFalse(many.writable)
        val huge = (1..20_000).joinToString(",", "[", "]") { """{"id":"p$it","extends":"codex","label":"P","enabled":true}""" }
        val started = System.nanoTime()
        val h = list(huge)
        assertEquals(ProfileLimits.PROFILES, h.profiles.size)
        assertFalse(h.writable)
        assertTrue("read in bounded time", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test fun aListTheValidatorWouldRefuseIsNeverWritable() {
        for (bad in listOf(
            """[{"id":"Bad_ID","extends":"codex","label":"A","enabled":true}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true},{"id":"a","extends":"pi","label":"B","enabled":true}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"env":{"K":5}}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"models":[{"id":"model"}]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"models":[{"id":"x","isDefault":true},{"id":"y","isDefault":true}]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"dropEnv":["A-B"]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"order":-1}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"verifiedThrough":"  "}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"command":[]}]""",
        )) {
            assertFalse(bad, list(bad).writable)
        }
        // A frame that was not the whole registry (a non-object entry dropped).
        assertFalse(ProvidersList.of(frame("""{"type":"providers","profiles":[$work,1]}"""), 1).writable)
        assertFalse(ProvidersList.of(frame("""{"type":"providers"}"""), 1).writable)
        // A model named "model" WITH a label is valid (lib/providers-registry.mjs:138).
        assertTrue(list("""[{"id":"a","extends":"acp","label":"A","enabled":true,"models":[{"id":"model","label":"Model"}]}]""").writable)
    }

    // ---- each edit, as the exact list the web sends -------------------------------------------------

    @Test fun addRemoveAndTheSwitchSendTheWholeList() {
        assertEquals(profiles(gemini, work, """{"id":"profile","extends":"claude","label":"profile","enabled":true}"""), sent(ProvidersPatch.write(two, ProfileEdit.Add)))
        val withOne = list("""[{"id":"profile","extends":"claude","label":"profile","enabled":true}]""")
        assertEquals("""profile-2""", (sent(ProvidersPatch.write(withOne, ProfileEdit.Add))["profiles"] as JsonArray)[1].jsonObject["id"]!!.let { (it as JsonPrimitive).content })
        assertEquals(profiles(work), sent(ProvidersPatch.write(two, ProfileEdit.Remove("gemini"))))
        assertEquals(profiles(gemini, work.replace("\"enabled\":false", "\"enabled\":true")), sent(ProvidersPatch.write(two, ProfileEdit.Enabled("work"))))
        assertEquals(profiles(), sent(ProvidersPatch.write(list("[$work]"), ProfileEdit.Remove("work"))))
    }

    @Test fun labelIdAndExtendsFollowTheWebsBlur() {
        assertEquals(profiles(gemini, work.replace("\"label\":\"Work\"", "\"label\":\"Day job\"")), sent(ProvidersPatch.write(two, ProfileEdit.Label("work", "  Day job "))))
        assertNull("empty", ProvidersPatch.write(two, ProfileEdit.Label("work", "   ")))
        assertNull("unchanged", ProvidersPatch.write(two, ProfileEdit.Label("work", "Work ")))
        assertEquals(profiles(gemini, work.replace("\"id\":\"work\"", "\"id\":\"day\"")), sent(ProvidersPatch.write(two, ProfileEdit.Rename("work", " day"))))
        assertNull(ProvidersPatch.write(two, ProfileEdit.Rename("work", "work")))
        assertEquals(profiles(gemini, work.replace("\"extends\":\"claude\"", "\"extends\":\"pi\"")), sent(ProvidersPatch.write(two, ProfileEdit.Extends("work", "pi"))))
        assertNull("outside the closed set", ProvidersPatch.write(two, ProfileEdit.Extends("work", "gemini")))
    }

    @Test fun theEnvEditorsEditsKeepEveryOtherValueAsItCame() {
        // A value (not trimmed), a key rename (trimmed; moves to the end, as delete + assign does), a remove, an add.
        val g = gemini
        assertEquals(profiles(g.replace("\"MODE\":\"x\"", "\"MODE\":\" y \""), work), sent(ProvidersPatch.write(two, ProfileEdit.EnvValue("gemini", "MODE", SecretText(" y ")))))
        assertEquals(
            profiles(g.replace("{\"GEMINI_API_KEY\":\"$sentinel\",\"MODE\":\"x\"}", "{\"MODE\":\"x\",\"GOOGLE_API_KEY\":\"$sentinel\"}"), work),
            sent(ProvidersPatch.write(two, ProfileEdit.EnvKey("gemini", "GEMINI_API_KEY", " GOOGLE_API_KEY "))),
        )
        assertEquals(profiles(g.replace("\"GEMINI_API_KEY\":\"$sentinel\",", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.EnvRemove("gemini", "GEMINI_API_KEY"))))
        assertEquals(
            profiles(gemini, work.replace(",\"enabled\":false", ",\"enabled\":false,\"env\":{\"TOKEN\":\"t \"}")),
            sent(ProvidersPatch.write(two, ProfileEdit.EnvAdd("work", " TOKEN ", SecretText("t ")))),
        )
        // The last key removed: no env key at all (`commit` sends undefined).
        val one = list("""[{"id":"a","extends":"codex","label":"A","env":{"K":"v"},"enabled":true}]""")
        assertEquals(profiles("""{"id":"a","extends":"codex","label":"A","enabled":true}"""), sent(ProvidersPatch.write(one, ProfileEdit.EnvRemove("a", "K"))))
        assertNull("an unchanged value", ProvidersPatch.write(two, ProfileEdit.EnvValue("gemini", "MODE", SecretText("x"))))
        assertNull("a key that is gone", ProvidersPatch.write(two, ProfileEdit.EnvValue("gemini", "GONE", SecretText("x"))))
        assertNull("an empty name", ProvidersPatch.write(two, ProfileEdit.EnvAdd("work", "  ", SecretText("x"))))
    }

    @Test fun theListFieldsOrderAndVerifiedThroughFollowTheWeb() {
        assertEquals(profiles(gemini.replace("[\"GEMINI\"]", "[\"GEMINI\",\"GOOGLE\"]"), work), sent(ProvidersPatch.write(two, ProfileEdit.DropEnv("gemini", " GEMINI , GOOGLE,, "))))
        assertEquals(profiles(gemini.replace(",\"dropEnv\":[\"GEMINI\"]", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.DropEnv("gemini", " "))))
        assertNull(ProvidersPatch.write(two, ProfileEdit.DropEnv("gemini", "GEMINI ,")))
        assertEquals(profiles(gemini, work.replace(",\"enabled\":false", ",\"enabled\":false,\"disallowedTools\":[\"WebSearch\",\"Task\"]")), sent(ProvidersPatch.write(two, ProfileEdit.DisallowedTools("work", "WebSearch, Task"))))
        assertEquals(profiles(gemini.replace("\"order\":2", "\"order\":10"), work), sent(ProvidersPatch.write(two, ProfileEdit.Order("gemini", "10"))))
        assertEquals(profiles(gemini.replace(",\"order\":2", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.Order("gemini", ""))))
        for (bad in listOf("2", "6e4", "-5", "1.5", "1000001")) assertNull(bad, ProvidersPatch.write(two, ProfileEdit.Order("gemini", bad)))
        assertEquals(profiles(gemini.replace("0.43.0", "0.44.1"), work), sent(ProvidersPatch.write(two, ProfileEdit.VerifiedThrough("gemini", " 0.44.1 "))))
        assertEquals(profiles(gemini.replace(",\"verifiedThrough\":\"0.43.0\"", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.VerifiedThrough("gemini", ""))))
    }

    @Test fun theModelListsFollowIssue107() {
        fun model(op: ModelOp) = ProvidersPatch.write(two, ProfileEdit.Model("work", ModelList.Models, op))
        val rows = """[{"id":"opus","isDefault":true},{"id":"sonnet","label":"Sonnet"}]"""
        fun with(next: String) = profiles(gemini, work.replace(rows, next))
        assertEquals(with("""[{"id":"opus-4","isDefault":true},{"id":"sonnet","label":"Sonnet"}]"""), sent(model(ModelOp.SetId(0, "opus", " opus-4 "))))
        assertEquals(with("""[{"id":"opus","isDefault":true},{"id":"sonnet"}]"""), sent(model(ModelOp.SetLabel(1, "sonnet", " "))))
        assertEquals(with("""[{"id":"opus"},{"id":"sonnet","label":"Sonnet","isDefault":true}]"""), sent(model(ModelOp.SetDefault(1, "sonnet"))))
        assertEquals(with("""[{"id":"sonnet","label":"Sonnet"}]"""), sent(model(ModelOp.Remove(0, "opus"))))
        assertEquals(with("""[{"id":"opus","isDefault":true},{"id":"sonnet","label":"Sonnet"},{"id":"haiku","label":"Haiku"}]"""), sent(model(ModelOp.Add("model", "haiku", "Haiku"))))
        // #107: an untouched placeholder, an empty id, or a placeholder-shaped id without a label is never committed.
        assertNull(model(ModelOp.Add("model", "model", "")))
        assertNull(model(ModelOp.Add("model", "  ", "x")))
        assertNull(model(ModelOp.Add("model", "model-2", "")))
        assertNotNull("a placeholder-shaped id WITH a label is a real one", model(ModelOp.Add("model", "model-2", "Two")))
        assertNull(model(ModelOp.SetId(0, "opus", "model")))
        // A row named by an index whose id no longer matches (the list changed meanwhile) takes nothing.
        assertNull(model(ModelOp.SetId(0, "sonnet", "x")))
        assertEquals("model", ProvidersPatch.draftModelId(emptyList()))
        assertEquals("model-3", ProvidersPatch.draftModelId(listOf(ProfileModel("model", "a", false), ProfileModel("model-2", "b", false))))
        // The last row removed: no models key.
        val one = list("""[{"id":"a","extends":"codex","label":"A","models":[{"id":"x"}],"enabled":true}]""")
        assertEquals(profiles("""{"id":"a","extends":"codex","label":"A","enabled":true}"""), sent(ProvidersPatch.write(one, ProfileEdit.Model("a", ModelList.Models, ModelOp.Remove(0, "x")))))
        assertEquals(
            profiles("""{"id":"a","extends":"codex","label":"A","models":[{"id":"x"}],"enabled":true,"additionalModels":[{"id":"y"}]}"""),
            sent(ProvidersPatch.write(one, ProfileEdit.Model("a", ModelList.Additional, ModelOp.Add("model", "y", "")))),
        )
    }

    @Test fun anUnknownKeyGoesBackExactlyAsItCame() {
        val future = list("""[{"id":"a","extends":"codex","label":"A","enabled":true,"future":{"nested":[1,"two"]}}]""")
        assertEquals(
            profiles("""{"id":"a","extends":"codex","label":"B","enabled":true,"future":{"nested":[1,"two"]}}"""),
            sent(ProvidersPatch.write(future, ProfileEdit.Label("a", "B"))),
        )
    }

    // ---- what a profile runs ---------------------------------------------------------------------

    @Test fun theCommandSplitsAsTheWebSplitsIt() {
        assertEquals(listOf("gemini", "--acp"), ProvidersPatch.commandParts("  gemini \t --acp\n"))
        assertEquals(emptyList<String>(), ProvidersPatch.commandParts(" \u3000 "))
        assertEquals(listOf("a", "b"), ProvidersPatch.commandParts("a\u00A0b"))
        assertEquals(listOf("a\u200Bb"), ProvidersPatch.commandParts("a\u200Bb"))
        assertFalse(ProvidersPatch.commandFits(List(33) { "p" }))
        assertFalse(ProvidersPatch.commandFits(listOf("x".repeat(257))))
    }

    @Test fun aConfirmedCommandOrHomeChangesOnlyThatKey() {
        assertEquals(profiles(gemini.replace("[\"gemini\",\"--acp\"]", "[\"/opt/gemini\",\"--acp\",\"-v\"]"), work),
            sent(ProvidersPatch.confirmed(two, ProfileRunsEdit.Command("gemini", listOf("/opt/gemini", "--acp", "-v")))))
        assertEquals(profiles(gemini.replace(",\"command\":[\"gemini\",\"--acp\"]", ""), work), sent(ProvidersPatch.confirmed(two, ProfileRunsEdit.Command("gemini", emptyList()))))
        assertEquals(profiles(gemini, work.replace("/srv/homes/work", "/srv/homes/w2")), sent(ProvidersPatch.confirmed(two, ProfileRunsEdit.Home("work", "/srv/homes/w2"))))
        assertEquals(profiles(gemini, work.replace(",\"homeDir\":\"/srv/homes/work\"", "")), sent(ProvidersPatch.confirmed(two, ProfileRunsEdit.Home("work", ""))))
        assertNull("the same words", ProvidersPatch.confirmed(two, ProfileRunsEdit.Command("gemini", listOf("gemini", "--acp"))))
        assertNull("the same home", ProvidersPatch.confirmed(two, ProfileRunsEdit.Home("work", "/srv/homes/work")))
        assertNull("a gone profile", ProvidersPatch.confirmed(two, ProfileRunsEdit.Home("gone", "/x")))
        assertTrue(ProvidersPatch.confirmed(two, ProfileRunsEdit.Home("work", "/x"))!!.isConfirmed)
    }

    @Test fun theSendRuleRefusesAnUnconfirmedChangeToWhatRuns() {
        // Hand-built writes (as a careless caller could build one): each changes a command or home.
        fun forged(vararg p: String, renames: Map<String, String> = emptyMap(), confirmed: ProfileRunsEdit? = null) =
            ProvidersWrite(p.map(::json), two.generation, confirmed, renames)
        val evilCommand = gemini.replace("[\"gemini\",\"--acp\"]", "[\"/tmp/evil\"]")
        assertNotNull(ProvidersPatch.refusal(forged(evilCommand, work), two))
        assertNotNull(ProvidersPatch.refusal(forged(gemini.replace(",\"command\":[\"gemini\",\"--acp\"]", ""), work), two))
        assertNotNull(ProvidersPatch.refusal(forged(gemini, work.replace("/srv/homes/work", "/tmp/evil-home")), two))
        assertNotNull("a new profile that runs something", ProvidersPatch.refusal(forged(gemini, work, """{"id":"n","extends":"pi","label":"N","command":["/tmp/x"],"enabled":true}"""), two))
        assertNotNull("a rename that also swaps the command", ProvidersPatch.refusal(forged(evilCommand.replace("\"id\":\"gemini\"", "\"id\":\"g2\""), work, renames = mapOf("g2" to "gemini")), two))
        assertNotNull("a confirmation of ANOTHER profile", ProvidersPatch.refusal(forged(evilCommand, work.replace("/srv/homes/work", "/x"), confirmed = ProfileRunsEdit.Home("work", "/x")), two))
        assertNotNull("a confirmation of another value", ProvidersPatch.refusal(forged(evilCommand, work, confirmed = ProfileRunsEdit.Command("gemini", listOf("/opt/ok"))), two))
        // What the builders make passes: a plain edit, a rename (the profile keeps what it runs), a confirmed change.
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Label("work", "W"))!!, two))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Rename("gemini", "g2"))!!, two))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Add)!!, two))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Remove("gemini"))!!, two))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.confirmed(two, ProfileRunsEdit.Command("gemini", listOf("/opt/ok")))!!, two))
    }

    // ---- concurrent edits ------------------------------------------------------------------------

    @Test fun aWriteIsBuiltFromTheNewestListAndAnOlderOneIsRefused() {
        val mine = ProvidersPatch.write(two, ProfileEdit.Label("work", "W"))!!
        // Another client toggles gemini meanwhile: the broadcast is the newest list.
        val broadcast = list("[${gemini.replace("\"enabled\":true", "\"enabled\":false")},$work]", generation = 2)
        assertEquals("built from an older list", ProvidersPatch.refusal(mine, broadcast))
        // Rebuilt from the broadcast, the same edit keeps the other client's change.
        val again = ProvidersPatch.write(broadcast, ProfileEdit.Label("work", "W"))!!
        assertNull(ProvidersPatch.refusal(again, broadcast))
        assertEquals(profiles(gemini.replace("\"enabled\":true", "\"enabled\":false"), work.replace("\"label\":\"Work\"", "\"label\":\"W\"")), sent(again))
        // A command changed elsewhere is never undone by a write that does not confirm it.
        val moved = list("[${gemini.replace("[\"gemini\",\"--acp\"]", "[\"/opt/new\"]")},$work]", generation = 3)
        assertNotNull(ProvidersPatch.refusal(again, moved))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(moved, ProfileEdit.Label("work", "W"))!!, moved))
    }

    @Test fun anUnwritableListIsNeverWrittenTo() {
        val odd = ProvidersList.of(frame("""{"type":"providers","profiles":[$work,1]}"""), 1)
        assertNull(ProvidersPatch.write(odd, ProfileEdit.Label("work", "W")))
        assertNull(ProvidersPatch.confirmed(odd, ProfileRunsEdit.Home("work", "/x")))
        assertEquals("no writable list", ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Add)!!, odd))
        assertEquals("no writable list", ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Add)!!, null))
    }

    // ---- over a socket ---------------------------------------------------------------------------

    private val h = ConnectionHarness()

    @After fun tearDown() = h.close()

    private fun origin() = serverOrigin(h.server.url("/").toString())!!

    private fun holdingProviders(): okhttp3.WebSocket {
        h.newClient()
        h.enqueueConnect()
        h.client.start()
        val ws = h.nextSocket()
        h.handshake(ws)
        ws.send("""{"type":"providers","profiles":[$gemini,$work]}""")
        h.await(h.client.providerProfiles) { it != null }
        return ws
    }

    @Test fun theListArrivesAndEachBroadcastIsTheNextGeneration() {
        val ws = holdingProviders()
        assertTrue(h.client.requestProviders())
        assertEquals(json("""{"type":"providers"}"""), h.expectFrame("providers"))
        val first = h.client.providerProfiles.value!!
        ws.send("""{"type":"providers","profiles":[$work]}""")
        h.await(h.client.providerProfiles) { it != null && it.generation > first.generation }
        assertEquals(listOf("work"), h.client.providerProfiles.value!!.profiles.map { it.id })
    }

    @Test fun theClientSendsOnlyAWriteBuiltFromItsNewestListToItsOwnServer() {
        val ws = holdingProviders()
        val newest = h.client.providerProfiles.value!!
        val write = ProvidersPatch.write(newest, ProfileEdit.Label("work", "W"))!!
        assertFalse("bound to its server", h.client.setProviders(write, "https://elsewhere.example"))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        assertTrue(h.client.setProviders(write, origin()))
        assertEquals(sent(write), h.expectFrame("set-providers"))
        // A broadcast lands: the write built before it is refused, nothing goes out.
        ws.send("""{"type":"providers","profiles":[$gemini,${work.replace("\"enabled\":false", "\"enabled\":true")}]}""")
        h.await(h.client.providerProfiles) { it != null && it.generation > newest.generation }
        assertFalse(h.client.setProviders(write, origin()))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    @Test fun theClientRefusesAnUnconfirmedChangeToWhatAProfileRuns() {
        holdingProviders()
        val newest = h.client.providerProfiles.value!!
        val forged = ProvidersWrite(listOf(json(gemini.replace("[\"gemini\",\"--acp\"]", "[\"/tmp/evil\"]")), json(work)), newest.generation, confirmed = null)
        assertFalse(h.client.setProviders(forged, origin()))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        val confirmed = ProvidersPatch.confirmed(newest, ProfileRunsEdit.Command("gemini", listOf("/opt/gemini")))!!
        assertTrue(h.client.setProviders(confirmed, origin()))
        assertEquals(sent(confirmed), h.expectFrame("set-providers"))
    }

    @Test fun aSignOutDropsTheList() {
        holdingProviders()
        h.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{}"))
        kotlinx.coroutines.runBlocking { h.client.logout() }
        assertNull(h.client.providerProfiles.value)
    }

    @Test fun authRequiredDropsTheList() {
        val ws = holdingProviders()
        ws.close(4001, "device revoked")
        h.await(h.client.connection) { it == ConnectionState.AuthRequired }
        assertNull(h.client.providerProfiles.value)
    }

    @Test fun aServerSwitchAndASettingsClearDropTheList() {
        val sync = SidebarSync()
        assertTrue(sync.onFrame(frame("""{"type":"providers","profiles":[$work]}""")))
        sync.clear()
        assertNull(sync.providerProfiles.value)
        assertTrue(sync.onFrame(frame("""{"type":"providers","profiles":[$work]}""")))
        val second = sync.providerProfiles.value!!.generation
        sync.clearSettings()
        assertNull(sync.providerProfiles.value)
        // A list after a clear is never numbered like one before it.
        sync.onFrame(frame("""{"type":"providers","profiles":[$work]}"""))
        assertTrue(sync.providerProfiles.value!!.generation > second)
    }

    /** acp-agents is decode-only: a frame changes nothing the client publishes, and the client has no way to send either retired message. */
    @Test fun theRetiredAcpAgentsFrameIsInert() {
        val ws = holdingProviders()
        val before = h.client.providerProfiles.value
        ws.send("""{"type":"acp-agents","agents":[{"id":"g","label":"G","command":"gemini","args":[],"homeDir":"/h","env":{"K":"$sentinel"},"enabled":true}]}""")
        // A barrier frame after it: once it is handled, the acp frame was too.
        ws.send("""{"type":"providers","profiles":[$work]}""")
        h.await(h.client.providerProfiles) { it != null && it !== before && it.profiles.size == 1 }
        assertEquals(listOf("work"), h.client.providerProfiles.value!!.profiles.map { it.id })
        val methods = TetherClient::class.java.methods.map { it.name.lowercase() }
        assertTrue(methods.none { it.contains("acp") })
    }

    @Test fun nothingIsSentWithoutAHandshakenSocket() {
        h.newClient(configured = false)
        assertFalse(h.client.requestProviders())
        assertFalse(h.client.setProviders(ProvidersPatch.write(two, ProfileEdit.Add)!!, "http://localhost"))
    }
}
