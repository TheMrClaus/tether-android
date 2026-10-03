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
 * newest list (a concurrent broadcast is never overwritten). ta-coik.5: every edit, the command,
 * home, engine and any env key included, is sent at once as the web sends it (no confirmation), and
 * an env add or rename onto an existing name overwrites it as the web does.
 */
class ProfilesTest {
    private val sentinel = "SENTINEL-env-81d0-do-not-leak"

    private fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject

    private fun frame(text: String) = ServerMessage.parse(text) as ServerMessage.Providers

    private fun list(profiles: String, generation: Long = 1, epoch: Long = 0) = ProvidersList.of(frame("""{"type":"providers","profiles":$profiles}"""), generation, epoch)

    private val gemini = """{"id":"gemini","extends":"acp","label":"Gemini","command":["gemini","--acp"],"homeDir":"/srv/homes/gemini",""" +
        """"env":{"GEMINI_API_KEY":"$sentinel","MODE":"x"},"dropEnv":["GEMINI"],"enabled":true,"order":2,"verifiedThrough":"0.43.0"}"""
    private val work = """{"id":"work","extends":"claude","label":"Work","homeDir":"/srv/homes/work","models":[{"id":"opus","isDefault":true},{"id":"sonnet","label":"Sonnet"}],"enabled":false}"""
    private val two = list("[$gemini,$work]")

    private fun sent(write: ProvidersWrite?): JsonObject = json(write!!.message.encode())

    private fun profiles(vararg p: String) = json("""{"type":"set-providers","profiles":[${p.joinToString(",")}]}""")

    // ---- the read ----------------------------------------------------------------------------------

    @Test fun theRecordedListReads() {
        val wire = File(System.getProperty("parity.corpus") ?: "../../parity-corpus", "wire/settings.jsonl")
        val last = wire.readLines().filter { it.contains("\"type\":\"providers\",\"profiles\":[{") }.last()
        val l = ProvidersList.of(ServerMessage.parse(json(last)["frame"]!!.jsonObject) as ServerMessage.Providers, 1)
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

    @Test fun aHostileListIsReadCappedAndStillWrittenBackWhole() {
        // Wrong types everywhere: read as absent, the frame and its other entries survive.
        val oddEntry = """{"id":7,"extends":"nope","label":["x"],"command":"gemini","homeDir":{},"env":["$sentinel"],"dropEnv":"A","models":[1,{"id":2}],"enabled":"yes","order":"3"}"""
        val odd = list("[$oddEntry]")
        val p = odd.profiles.single()
        assertEquals(listOf("", "", "", false), listOf(p.id, p.extends, p.label, p.enabled))
        assertNull(p.command)
        assertNull(p.homeDir)
        assertEquals(emptyList<String>(), p.envKeys)
        assertEquals(emptyList<ProfileModel>(), p.models)
        assertNull(p.order)
        // ta-coik.17: written back as it came, with the edit, as the web's `[...profiles, entry]` is (the server judges it).
        assertEquals(profiles(oddEntry, """{"id":"profile","extends":"claude","label":"profile","enabled":true}"""), sent(ProvidersPatch.write(odd, ProfileEdit.Add)))
        // Past the server's limits: a 5000-unit label, 100 env keys, 40 command parts, 20000 profiles.
        val longLabel = """{"id":"a","extends":"codex","label":"${"x".repeat(5000)}","enabled":true}"""
        val long = list("[$longLabel]")
        assertEquals("", long.profiles.single().label)
        assertEquals(profiles(longLabel.replace("\"enabled\":true", "\"enabled\":false")), sent(ProvidersPatch.write(long, ProfileEdit.Enabled("a", false))))
        val env = (1..100).joinToString(",") { "\"K$it\":\"v\"" }
        val many = list("""[{"id":"a","extends":"codex","label":"A","enabled":true,"env":{$env},"command":[${(1..40).joinToString(",") { "\"p\"" }}]}]""")
        assertEquals(ProfileLimits.ENV_KEYS, many.profiles.single().envKeys.size)
        assertEquals(ProfileLimits.COMMAND, many.profiles.single().command!!.size)
        val manySent = (sent(ProvidersPatch.write(many, ProfileEdit.Label("a", "B")))["profiles"] as JsonArray)[0].jsonObject
        assertEquals(100, (manySent["env"] as JsonObject).size)
        assertEquals(40, (manySent["command"] as JsonArray).size)
        val huge = (1..20_000).joinToString(",", "[", "]") { """{"id":"p$it","extends":"codex","label":"P","enabled":true}""" }
        val started = System.nanoTime()
        val h = list(huge)
        assertEquals(ProfileLimits.PROFILES, h.profiles.size)
        assertTrue("read in bounded time", System.nanoTime() - started < 5_000_000_000L)
        assertEquals("every entry goes back", 20_001, (sent(ProvidersPatch.write(h, ProfileEdit.Add))["profiles"] as JsonArray).size)
    }

    /** ta-coik.17: a list the server's validator would refuse is edited all the same, as on the web (the server answers). */
    @Test fun aListTheValidatorWouldRefuseIsStillEdited() {
        for (bad in listOf(
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"env":{"K":5}}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"models":[{"id":"model"}]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"models":[{"id":"x","isDefault":true},{"id":"y","isDefault":true}]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"dropEnv":["A-B"]}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"order":-1}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"verifiedThrough":"  "}]""",
            """[{"id":"a","extends":"codex","label":"A","enabled":true,"command":[]}]""",
        )) {
            val l = list(bad)
            val write = ProvidersPatch.write(l, ProfileEdit.Label("a", "B"))
            assertNotNull(bad, write)
            assertNull(bad, ProvidersPatch.refusal(write!!, l))
        }
        val badId = list("""[{"id":"Bad_ID","extends":"codex","label":"A","enabled":true}]""")
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(badId, ProfileEdit.Add)!!, badId))
    }

    /** settings-dialog.tsx :634-636: `update(id, patch)` and `remove(id)` act on EVERY entry with the id. */
    @Test fun twoEntriesWithOneIdAreBothEditedAsTheWebsMapIs() {
        val dup = list("""[{"id":"a","extends":"codex","label":"A","enabled":true},{"id":"a","extends":"pi","label":"B","enabled":false,"x":1}]""")
        assertEquals(
            profiles("""{"id":"a","extends":"codex","label":"C","enabled":true}""", """{"id":"a","extends":"pi","label":"C","enabled":false,"x":1}"""),
            sent(ProvidersPatch.write(dup, ProfileEdit.Label("a", "C"))),
        )
        assertEquals(profiles(), sent(ProvidersPatch.write(dup, ProfileEdit.Remove("a"))))
        val home = list("""[{"id":"a","extends":"codex","label":"A","homeDir":"/h","enabled":true},{"id":"a","extends":"pi","label":"B","homeDir":"/g","enabled":false}]""")
        assertEquals(
            profiles("""{"id":"a","extends":"codex","label":"A","enabled":true}""", """{"id":"a","extends":"pi","label":"B","enabled":false}"""),
            sent(ProvidersPatch.write(home, ProfileEdit.Home("a", ""))),
        )
    }

    // ---- each edit, as the exact list the web sends -------------------------------------------------

    @Test fun addRemoveAndTheSwitchSendTheWholeList() {
        assertEquals(profiles(gemini, work, """{"id":"profile","extends":"claude","label":"profile","enabled":true}"""), sent(ProvidersPatch.write(two, ProfileEdit.Add)))
        val withOne = list("""[{"id":"profile","extends":"claude","label":"profile","enabled":true}]""")
        assertEquals("""profile-2""", (sent(ProvidersPatch.write(withOne, ProfileEdit.Add))["profiles"] as JsonArray)[1].jsonObject["id"]!!.let { (it as JsonPrimitive).content })
        assertEquals(profiles(work), sent(ProvidersPatch.write(two, ProfileEdit.Remove("gemini"))))
        assertEquals(profiles(gemini, work.replace("\"enabled\":false", "\"enabled\":true")), sent(ProvidersPatch.write(two, ProfileEdit.Enabled("work", true))))
        assertEquals(profiles(), sent(ProvidersPatch.write(list("[$work]"), ProfileEdit.Remove("work"))))
    }

    @Test fun labelIdAndExtendsFollowTheWebsBlur() {
        assertEquals(profiles(gemini, work.replace("\"label\":\"Work\"", "\"label\":\"Day job\"")), sent(ProvidersPatch.write(two, ProfileEdit.Label("work", "  Day job "))))
        assertNull("empty", ProvidersPatch.write(two, ProfileEdit.Label("work", "   ")))
        assertNull("unchanged", ProvidersPatch.write(two, ProfileEdit.Label("work", "Work ")))
        assertEquals(profiles(gemini, work.replace("\"id\":\"work\"", "\"id\":\"day\"")), sent(ProvidersPatch.write(two, ProfileEdit.Rename("work", " day"))))
        assertNull(ProvidersPatch.write(two, ProfileEdit.Rename("work", "work")))
        // ta-coik.5: Extends is sent at once, as the web's select does (:696).
        assertEquals(profiles(gemini, work.replace("\"extends\":\"claude\"", "\"extends\":\"pi\"")), sent(ProvidersPatch.write(two, ProfileEdit.Extends("work", "pi"))))
        assertEquals(ProvidersBuild.NoChange, ProvidersPatch.build(two, ProfileEdit.Extends("work", "claude")))
        // ta-coik.17: any value is sent; the server says if it refuses it.
        assertEquals(profiles(gemini, work.replace("\"extends\":\"claude\"", "\"extends\":\"gemini\"")), sent(ProvidersPatch.write(two, ProfileEdit.Extends("work", "gemini"))))
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
        // :806-811 `Number(raw)` of the number input's value: any integer but the profile's is written (the server judges it).
        for ((typed, n) in listOf("6e4" to "60000", "-5" to "-5", "1000001" to "1000001", "2.0" to null, "1.5" to null, "2" to null)) {
            val w = ProvidersPatch.write(two, ProfileEdit.Order("gemini", typed))
            if (n == null) assertNull(typed, w) else assertEquals(typed, profiles(gemini.replace("\"order\":2", "\"order\":$n"), work), sent(w))
        }
        // Not a number the input keeps (its value is then ""): the order is cleared, as on the web.
        for (typed in listOf("1-2", "e", "+5", "0x10")) assertEquals(typed, profiles(gemini.replace(",\"order\":2", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.Order("gemini", typed))))
        assertNull(ProvidersPatch.write(list("[$work]"), ProfileEdit.Order("work", "1-2")))
        assertEquals(profiles(gemini.replace("0.43.0", "v".repeat(100)), work), sent(ProvidersPatch.write(two, ProfileEdit.VerifiedThrough("gemini", "v".repeat(100)))))
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
        // #107: an untouched placeholder or an empty id is never committed (:491-497); any other id is, as on the web.
        assertNull(model(ModelOp.Add("model", "model", "")))
        assertNull(model(ModelOp.Add("model", "  ", "x")))
        assertEquals(with("""[{"id":"opus","isDefault":true},{"id":"sonnet","label":"Sonnet"},{"id":"model-2"}]"""), sent(model(ModelOp.Add("model", "model-2", ""))))
        assertEquals(with("""[{"id":"model","isDefault":true},{"id":"sonnet","label":"Sonnet"}]"""), sent(model(ModelOp.SetId(0, "opus", "model"))))
        val longLabel = "l".repeat(300)
        assertEquals(with("""[{"id":"opus","isDefault":true},{"id":"sonnet","label":"$longLabel"}]"""), sent(model(ModelOp.SetLabel(1, "sonnet", longLabel))))
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
    }

    @Test fun theCommandAndHomeAreSentAtOnceAsTheWebsBlurSendsThem() {
        assertEquals(profiles(gemini.replace("[\"gemini\",\"--acp\"]", "[\"/opt/gemini\",\"--acp\",\"-v\"]"), work),
            sent(ProvidersPatch.write(two, ProfileEdit.Command("gemini", "  /opt/gemini  --acp\t-v "))))
        assertEquals(profiles(gemini.replace(",\"command\":[\"gemini\",\"--acp\"]", ""), work), sent(ProvidersPatch.write(two, ProfileEdit.Command("gemini", "  "))))
        assertEquals(profiles(gemini, work.replace("/srv/homes/work", "/srv/homes/w2")), sent(ProvidersPatch.write(two, ProfileEdit.Home("work", " /srv/homes/w2 "))))
        assertEquals(profiles(gemini, work.replace(",\"homeDir\":\"/srv/homes/work\"", "")), sent(ProvidersPatch.write(two, ProfileEdit.Home("work", ""))))
        assertEquals("the same words", ProvidersBuild.NoChange, ProvidersPatch.build(two, ProfileEdit.Command("gemini", "gemini   --acp")))
        assertEquals("the same home", ProvidersBuild.NoChange, ProvidersPatch.build(two, ProfileEdit.Home("work", "/srv/homes/work ")))
        assertEquals("a gone profile", ProvidersBuild.Refused(ProvidersRefusal.Gone), ProvidersPatch.build(two, ProfileEdit.Home("gone", "/x")))
        // ta-coik.17: past the server's limits is sent all the same, as the web sends it (the server refuses it; its error is shown).
        assertEquals(profiles(gemini.replace("[\"gemini\",\"--acp\"]", List(33) { "\"p\"" }.joinToString(",", "[", "]")), work),
            sent(ProvidersPatch.write(two, ProfileEdit.Command("gemini", List(33) { "p" }.joinToString(" ")))))
        assertEquals(profiles(gemini, work.replace("/srv/homes/work", "h".repeat(5000))), sent(ProvidersPatch.write(two, ProfileEdit.Home("work", "h".repeat(5000)))))
        val keys = list("[${gemini.replace("\"MODE\":\"x\"", (1..63).joinToString(",") { "\"K$it\":\"v\"" })},$work]")
        val added = (sent(ProvidersPatch.write(keys, ProfileEdit.EnvAdd("gemini", "NEW", SecretText("v".repeat(5000)))))["profiles"] as JsonArray)[0].jsonObject
        assertEquals("a 65th env key, a 5000-unit value", 65, (added["env"] as JsonObject).size)
    }

    /** ta-coik.5: the send rule checks only that the write is built from the newest list (what runs is the web's to change, as it is the browser's). */
    @Test fun theSendRuleLetsAnyBuiltChangeGo() {
        for (edit in listOf(
            ProfileEdit.Command("gemini", "/opt/ok"),
            ProfileEdit.Home("work", "/x"),
            ProfileEdit.Extends("gemini", "codex"),
            ProfileEdit.EnvAdd("work", "LD_PRELOAD", SecretText("/tmp/e.so")),
            ProfileEdit.EnvKey("gemini", "MODE", "PATH"),
            ProfileEdit.Label("work", "W"),
            ProfileEdit.Rename("gemini", "g2"),
            ProfileEdit.Add,
            ProfileEdit.Remove("gemini"),
        )) {
            val write = ProvidersPatch.write(two, edit)
            assertNotNull(edit.toString(), write)
            assertNull(edit.toString(), ProvidersPatch.refusal(write!!, two))
        }
    }

    // ---- concurrent edits ------------------------------------------------------------------------

    @Test fun aWriteIsBuiltFromTheNewestListAndAnOlderOneIsRefused() {
        val mine = ProvidersPatch.write(two, ProfileEdit.Label("work", "W"))!!
        // Another client toggles gemini meanwhile: the broadcast is the newest list.
        val broadcast = list("[${gemini.replace("\"enabled\":true", "\"enabled\":false")},$work]", generation = 2)
        assertEquals(ProvidersRefusal.Stale, ProvidersPatch.refusal(mine, broadcast))
        // Rebuilt from the broadcast, the same edit keeps the other client's change.
        val again = ProvidersPatch.write(broadcast, ProfileEdit.Label("work", "W"))!!
        assertNull(ProvidersPatch.refusal(again, broadcast))
        assertEquals(profiles(gemini.replace("\"enabled\":true", "\"enabled\":false"), work.replace("\"label\":\"Work\"", "\"label\":\"W\"")), sent(again))
        // A command changed elsewhere is never undone by a write built before it (Stale), and is kept by one built after.
        val moved = list("[${gemini.replace("[\"gemini\",\"--acp\"]", "[\"/opt/new\"]")},$work]", generation = 3)
        assertEquals(ProvidersRefusal.Stale, ProvidersPatch.refusal(again, moved))
        val rebuilt = ProvidersPatch.write(moved, ProfileEdit.Label("work", "W"))!!
        assertNull(ProvidersPatch.refusal(rebuilt, moved))
        assertTrue(sent(rebuilt).toString().contains("/opt/new"))
    }

    /** ta-coik.17: a frame the app read only in part is edited from what it holds; only no list at all stops a write. */
    @Test fun onlyNoListStopsAWrite() {
        val odd = ProvidersList.of(frame("""{"type":"providers","profiles":[$work,1]}"""), 1)
        assertEquals(profiles(work.replace("\"label\":\"Work\"", "\"label\":\"W\"")), sent(ProvidersPatch.write(odd, ProfileEdit.Label("work", "W"))))
        assertNull(ProvidersPatch.refusal(ProvidersPatch.write(odd, ProfileEdit.Add)!!, odd))
        assertEquals(ProvidersBuild.Refused(ProvidersRefusal.NoList), ProvidersPatch.build(null, ProfileEdit.Add))
        assertEquals(ProvidersRefusal.NoList, ProvidersPatch.refusal(ProvidersPatch.write(two, ProfileEdit.Add)!!, null))
    }

    // ---- ta-coik.5: the env editor as the web's -------------------------------------------------

    /** Any key, PATH and LD_ names included, is added, changed, renamed and removed at once, as the web's EnvEditor does. */
    @Test fun everyEnvKeyIsEditedAtOnce() {
        val withPath = list("[${gemini.replace("\"MODE\":\"x\"", "\"MODE\":\"x\",\"PATH\":\"/usr/bin\"")},$work]")
        val g = gemini.replace("\"MODE\":\"x\"", "\"MODE\":\"x\",\"PATH\":\"/usr/bin\"")
        assertEquals(profiles(g.replace("\"PATH\":\"/usr/bin\"", "\"PATH\":\"/opt/bin\""), work), sent(ProvidersPatch.write(withPath, ProfileEdit.EnvValue("gemini", "PATH", SecretText("/opt/bin")))))
        assertEquals(profiles(g.replace(",\"PATH\":\"/usr/bin\"", ""), work), sent(ProvidersPatch.write(withPath, ProfileEdit.EnvRemove("gemini", "PATH"))))
        assertEquals(profiles(g.replace("\"PATH\":\"/usr/bin\"", "\"MYPATH\":\"/usr/bin\""), work), sent(ProvidersPatch.write(withPath, ProfileEdit.EnvKey("gemini", "PATH", "MYPATH"))))
        assertEquals(profiles(g.replace("\"PATH\":\"/usr/bin\"", "\"PATH\":\"/usr/bin\",\"HOME\":\"/srv/h\""), work), sent(ProvidersPatch.write(withPath, ProfileEdit.EnvAdd("gemini", "HOME", SecretText("/srv/h")))))
        assertEquals(
            profiles(gemini, work.replace(",\"enabled\":false", ",\"enabled\":false,\"env\":{\"LD_PRELOAD\":\"/tmp/e.so\"}")),
            sent(ProvidersPatch.write(two, ProfileEdit.EnvAdd("work", " LD_PRELOAD ", SecretText("/tmp/e.so")))),
        )
    }

    /** settings-dialog.tsx:367-373, 384-391: an add or a rename onto a name the profile has overwrites that entry in its place. */
    @Test fun anEnvAddOrRenameOntoAnExistingNameOverwritesItAsTheWebDoes() {
        // Add: `{ ...env, [key]: value }`: MODE keeps its place, with the new value.
        assertEquals(profiles(gemini.replace("\"MODE\":\"x\"", "\"MODE\":\"y\""), work), sent(ProvidersPatch.write(two, ProfileEdit.EnvAdd("gemini", " MODE ", SecretText("y")))))
        // Rename: `delete next[key]; next[nextKey] = value`: GEMINI_API_KEY's value lands on MODE, in MODE's place.
        assertEquals(
            profiles(gemini.replace("{\"GEMINI_API_KEY\":\"$sentinel\",\"MODE\":\"x\"}", "{\"MODE\":\"$sentinel\"}"), work),
            sent(ProvidersPatch.write(two, ProfileEdit.EnvKey("gemini", "GEMINI_API_KEY", "MODE"))),
        )
        // The same value again is no change.
        assertEquals(ProvidersBuild.NoChange, ProvidersPatch.build(two, ProfileEdit.EnvAdd("gemini", "MODE", SecretText("x"))))
    }

    /** r2 (security F8): the switch sends the value the user saw flipped to, so a concurrent flip is never undone by toggling it back. */
    @Test fun theSwitchSendsTheIntendedValue() {
        assertEquals(ProvidersBuild.NoChange, ProvidersPatch.build(two, ProfileEdit.Enabled("gemini", true)))
        assertEquals(profiles(gemini.replace("\"enabled\":true", "\"enabled\":false"), work), sent(ProvidersPatch.write(two, ProfileEdit.Enabled("gemini", false))))
    }

    @Test fun aWriteInFlightBlocksTheNextUntilAListContainsIt() {
        var now = 1_000L
        val inFlight = ProvidersInFlight(now = { now })
        val first = ProvidersPatch.write(two, ProfileEdit.Label("work", "W"))!!
        assertNull(inFlight.refusal(two))
        inFlight.sent(first, two)
        assertEquals(ProvidersRefusal.InFlight, inFlight.refusal(two))
        // r3 (verifier probe FINDING_anotherClientsBroadcastLiftsTheGuardBeforeOursLands): another
        // client's broadcast (a newer list without our write) does NOT lift it.
        val theirs = list("[${gemini.replace("\"enabled\":true", "\"enabled\":false")},$work]", generation = 2)
        assertEquals(ProvidersRefusal.InFlight, inFlight.refusal(theirs))
        assertEquals(ProvidersWriteStatus.Waiting(overdue = false), inFlight.status(theirs))
        // The list that holds our write does (the canonical key order may differ).
        val ours = list("[${gemini.replace("\"enabled\":true", "\"enabled\":false")},${work.replace("\"label\":\"Work\",", "").replace("\"enabled\":false", "\"enabled\":false,\"label\":\"W\"")}]", generation = 3)
        assertNull(inFlight.refusal(ours))
        assertEquals(ProvidersWriteStatus.Done(ProvidersWriteStatus.Outcome.Saved), inFlight.status(ours))
    }

    @Test fun anOverdueWriteIsAskedForAndTheReplyLiftsIt() {
        var now = 1_000L
        val inFlight = ProvidersInFlight(now = { now })
        inFlight.sent(ProvidersPatch.write(two, ProfileEdit.Remove("gemini"))!!, two)
        assertFalse("not yet", inFlight.overdue(two))
        now += ProvidersInFlight.TIMEOUT_MS
        assertTrue("overdue: ask the server once", inFlight.overdue(two))
        assertFalse(inFlight.overdue(two))
        // Still refused until the reply (the next list folded) lands, whatever it says.
        assertEquals(ProvidersRefusal.InFlight, inFlight.refusal(two))
        assertEquals(ProvidersWriteStatus.Waiting(overdue = true), inFlight.status(two))
        // r4: the reply does not hold the write: the server did not take it.
        assertNull(inFlight.refusal(list("[$gemini,$work]", generation = 2)))
        assertEquals(ProvidersWriteStatus.Done(ProvidersWriteStatus.Outcome.NotSaved), inFlight.status(list("[$gemini,$work]", generation = 2)))
        // A new socket lifts it as well.
        inFlight.sent(ProvidersPatch.write(two, ProfileEdit.Remove("gemini"))!!, two)
        assertNull(inFlight.refusal(list("[$gemini,$work]", generation = 3, epoch = 1)))
    }

    /**
     * ta-coik.4: an env key is any trimmed non-empty name, as the web's env editor takes it
     * (settings-dialog.tsx:366-372; lib/providers-registry.mjs sanitizeEnv bounds only its length).
     * ta-coik.5: and it is written at once, as on the web.
     */
    @Test fun anEnvKeyThatIsNotAPlainNameIsWrittenAtOnce() {
        for (k in listOf("LD_PRELOAD=/tmp/x.so:", "MY KEY", "A-B")) {
            val write = ProvidersPatch.write(two, ProfileEdit.EnvAdd("gemini", k, SecretText("v")))
            assertNotNull(k, write)
            val env = write!!.profiles.first { (it["id"] as JsonPrimitive).content == "gemini" }["env"] as JsonObject
            assertEquals(k, JsonPrimitive("v"), env[k])
            assertNull(k, ProvidersPatch.refusal(write, two))
        }
        val held = list("[${gemini.replace("\"MODE\":\"x\"", "\"MODE\":\"x\",\"A-B\":\"v\"")},$work]")
        assertTrue(ProvidersPatch.build(held, ProfileEdit.EnvValue("gemini", "A-B", SecretText("w"))) is ProvidersBuild.Ready)
        assertTrue(ProvidersPatch.build(held, ProfileEdit.EnvRemove("gemini", "A-B")) is ProvidersBuild.Ready)
        assertTrue(ProvidersPatch.build(held, ProfileEdit.EnvKey("gemini", "A-B", "AB")) is ProvidersBuild.Ready)
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
        assertEquals("bound to its server", ProvidersRefusal.NotConnected, h.client.setProviders(write, "https://elsewhere.example"))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        assertNull(h.client.setProviders(write, origin()))
        assertEquals(sent(write), h.expectFrame("set-providers"))
        // A broadcast lands: the write built before it is refused, nothing goes out.
        ws.send("""{"type":"providers","profiles":[$gemini,${work.replace("\"enabled\":false", "\"enabled\":true")}]}""")
        h.await(h.client.providerProfiles) { it != null && it.generation > newest.generation }
        assertEquals(ProvidersRefusal.Stale, h.client.setProviders(write, origin()))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
    }

    /** ta-coik.5: the client sends a command change built from its newest list at once, as the web does. */
    @Test fun theClientSendsACommandChangeAtOnce() {
        holdingProviders()
        val newest = h.client.providerProfiles.value!!
        val write = ProvidersPatch.write(newest, ProfileEdit.Command("gemini", "/opt/gemini"))!!
        assertNull(h.client.setProviders(write, origin()))
        assertEquals(sent(write), h.expectFrame("set-providers"))
    }

    /** r2 (security F1): a list from before a reconnect is never written back, and the client asks for the list again on the new socket. */
    @Test fun aListFromBeforeAReconnectIsNeverWrittenBackAndIsAskedForAgain() {
        val ws = holdingProviders()
        assertTrue(h.client.requestProviders())
        h.expectFrame("providers")
        val old = h.client.providerProfiles.value!!
        val stale = ProvidersPatch.write(old, ProfileEdit.Label("work", "W"))!!
        ws.close(1001, null)
        h.await(h.client.connection) { it == ConnectionState.Disconnected }
        h.enqueueConnect()
        h.scheduler.await(::isReconnectDelay).fire()
        val ws2 = h.nextSocket()
        h.handshake(ws2)
        // Asked for again on the new socket, before any edit could be built from it.
        assertEquals(json("""{"type":"providers"}"""), h.expectFrame("providers"))
        // Before the reply: the list held is the old socket's, and nothing built from it is sent.
        assertEquals(ProvidersRefusal.Stale, h.client.setProviders(stale, origin()))
        assertEquals(ProvidersRefusal.Stale, h.client.setProviders(ProvidersPatch.write(h.client.providerProfiles.value, ProfileEdit.Label("work", "X"))!!, origin()))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        // The new socket's list is written to.
        ws2.send("""{"type":"providers","profiles":[$gemini,$work]}""")
        h.await(h.client.providerProfiles) { it != null && it.epoch != old.epoch }
        assertNull(h.client.setProviders(ProvidersPatch.write(h.client.providerProfiles.value, ProfileEdit.Label("work", "X"))!!, origin()))
    }

    /** r2 (verifier F1): a second write before the first's broadcast would undo it: refused until the broadcast. */
    @Test fun aSecondWriteBeforeTheBroadcastIsRefused() {
        val ws = holdingProviders()
        val newest = h.client.providerProfiles.value!!
        val command = ProvidersPatch.write(newest, ProfileEdit.Command("gemini", "/opt/gemini"))!!
        assertNull(h.client.setProviders(command, origin()))
        h.expectFrame("set-providers")
        // Built from the same (not yet updated) list, it would put the old command back.
        assertEquals(ProvidersRefusal.InFlight, h.client.setProviders(ProvidersPatch.write(newest, ProfileEdit.Label("work", "W"))!!, origin()))
        assertEquals(emptyList<JsonObject>(), h.framesUntilBarrier())
        ws.send("""{"type":"providers","profiles":[${gemini.replace("[\"gemini\",\"--acp\"]", "[\"/opt/gemini\"]")},$work]}""")
        h.await(h.client.providerProfiles) { it != null && it.generation > newest.generation }
        val next = ProvidersPatch.write(h.client.providerProfiles.value, ProfileEdit.Label("work", "W"))!!
        assertNull(h.client.setProviders(next, origin()))
        assertTrue(h.expectFrame("set-providers").toString().contains("/opt/gemini"))
    }

    /** r3: an overdue write makes the client ask for the list; the reply lifts the guard. */
    @Test fun anOverdueWriteIsAskedForAgain() {
        val ws = holdingProviders()
        val newest = h.client.providerProfiles.value!!
        assertNull(h.client.setProviders(ProvidersPatch.write(newest, ProfileEdit.Label("work", "W"))!!, origin()))
        h.expectFrame("set-providers")
        ws.send("""{"type":"error","message":"each profile entry needs a valid id"}""")
        // r4: the server REFUSES it (an error, no broadcast); at the timeout the client asks for the registry.
        h.now.addAndGet(ProvidersInFlight.TIMEOUT_MS)
        h.scheduler.await { it == ProvidersInFlight.TIMEOUT_MS }.fire()
        assertEquals(json("""{"type":"providers"}"""), h.expectFrame("providers"))
        assertEquals(ProvidersRefusal.InFlight, h.client.setProviders(ProvidersPatch.write(newest, ProfileEdit.Label("work", "X"))!!, origin()))
        assertEquals(ProvidersWriteStatus.Waiting(overdue = true), h.client.providersWriteStatus())
        // The reply (the server refused the write: the list is as it was) lifts it, and says it was not saved.
        ws.send("""{"type":"providers","profiles":[$gemini,$work]}""")
        h.await(h.client.providerProfiles) { it != null && it.generation > newest.generation }
        assertEquals(ProvidersWriteStatus.Done(ProvidersWriteStatus.Outcome.NotSaved), h.client.providersWriteStatus())
        // The next edit IS sent.
        val next = ProvidersPatch.write(h.client.providerProfiles.value, ProfileEdit.Label("work", "X"))!!
        assertNull(h.client.setProviders(next, origin()))
        assertEquals(sent(next), h.expectFrame("set-providers"))
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
        assertEquals(ProvidersRefusal.NoList, h.client.setProviders(ProvidersPatch.write(two, ProfileEdit.Add)!!, "http://localhost"))
    }
}
