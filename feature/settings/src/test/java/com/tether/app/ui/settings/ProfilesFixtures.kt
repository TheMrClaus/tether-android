package com.tether.app.ui.settings

import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersPatch
import com.tether.app.client.ProvidersRefusal
import com.tether.app.client.ProvidersWrite
import com.tether.app.protocol.ServerMessage
import com.tether.app.protocol.TetherJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** ta-q6p: seeded `providers` frames (fake values only) and a recording writer that applies the client's send rule. */
object ProfileFixtures {
    const val ORIGIN = ServerFixtures.ORIGIN

    /** Behaviour tests: what must never leave a revealed env value. */
    const val SENTINEL = "SENTINEL-env-c3a9-do-not-leak"
    const val SENTINEL_2 = "SENTINEL-env-77e0-do-not-leak"

    /** Goldens: an obviously fake value for the revealed shot. */
    const val FAKE_KEY = "FAKE-demo-api-key-0000"

    fun gemini(env: String = FAKE_KEY, command: String = """["gemini","--experimental-acp"]""", extraEnv: String = "", extends: String = "acp") =
        """{"id":"gemini","extends":"$extends","label":"Gemini CLI","command":$command,"homeDir":"/srv/homes/gemini",""" +
            """"env":{"GEMINI_API_KEY":"$env"$extraEnv},"dropEnv":["GEMINI"],"enabled":true,"order":1,"verifiedThrough":"0.43.0"}"""

    const val WORK = """{"id":"claude-work","extends":"claude","label":"Claude Code (work)","homeDir":"/srv/homes/claude-work",""" +
        """"models":[{"id":"claude-opus-4","isDefault":true},{"id":"claude-sonnet-4","label":"Sonnet"}],"disallowedTools":["WebSearch"],"enabled":true}"""

    fun zai(token: String = "FAKE-demo-token-1111") =
        """{"id":"zai","extends":"claude","label":"Z.AI GLM","env":{"ANTHROPIC_AUTH_TOKEN":"$token","ANTHROPIC_BASE_URL":"https://api.z.ai/api/anthropic"},""" +
            """"dropEnv":["ANTHROPIC"],"enabled":false}"""

    fun profiles(vararg p: String) = "[${p.joinToString(",")}]"

    fun list(profiles: String = profiles(gemini(), WORK, zai()), generation: Long = 1, epoch: Long = 0L): ProvidersList =
        ProvidersList.of(ServerMessage.parse("""{"type":"providers","profiles":$profiles}""") as ServerMessage.Providers, generation, epoch)

    fun list(objects: List<JsonObject>, generation: Long, epoch: Long = 0L): ProvidersList =
        ProvidersList.of(ServerMessage.Providers(objects), generation, epoch)

    fun binding(list: ProvidersList? = list(), writer: ProvidersWriter = ProvidersWriter.None, origin: String? = ORIGIN) =
        ProvidersBinding(list, origin, writer)

    fun json(text: String): JsonObject = TetherJson.parseToJsonElement(text).jsonObject

    /** The frame a write sends. */
    fun frame(vararg profiles: String) = json("""{"type":"set-providers","profiles":[${profiles.joinToString(",")}]}""")
}

/**
 * Records every write with the origin it was bound to. Like the client, it refuses a write that
 * fails [ProvidersPatch.refusal] against [newest] (built from an older list, or an unconfirmed
 * change to what a profile runs); [reply] plays the server's broadcast.
 */
class RecordingProvidersWriter(
    private val newest: () -> ProvidersList?,
    private val reply: (ProvidersWrite) -> Unit = {},
) : ProvidersWriter {
    val writes = mutableListOf<Pair<ProvidersWrite, String>>()
    val refused = mutableListOf<ProvidersRefusal>()

    /** A refusal to answer every write with (as the client would, e.g. a write in flight). */
    var refuseWith: ProvidersRefusal? = null

    /** The frames as sent. */
    fun frames(): List<JsonObject> = writes.map { ProfileFixtures.json(it.first.message.encode()) }

    override fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal? {
        val why = refuseWith ?: ProvidersPatch.refusal(write, newest())
        if (why != null) {
            refused += why
            return why
        }
        writes += write to origin
        reply(write)
        return null
    }
}
