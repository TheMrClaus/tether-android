package com.tether.app.ui.settings

import com.tether.app.client.ProvidersList
import com.tether.app.client.ProvidersRefusal
import com.tether.app.client.ProvidersInFlight
import com.tether.app.client.ProvidersOutbox
import com.tether.app.client.ProvidersWriteStatus
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
 * Records every write with the origin it was bound to. Like the client (ta-coik.17 r2), one write
 * at a time through the same [ProvidersOutbox]: a write while the last one waits, or built from an
 * older list, is queued and rebuilt; [landed] is what the client does on each `providers` frame
 * (the queued edits go out once the write in flight is answered). [reply] plays the server's broadcast.
 */
class RecordingProvidersWriter(
    private val newest: () -> ProvidersList?,
    private val reply: (ProvidersWrite) -> Unit = {},
) : ProvidersWriter {
    /** The test's clock for the in-flight wait (r4: the same [ProvidersInFlight] the client runs). */
    var now = 1_000L
    private val outbox = ProvidersOutbox(ProvidersInFlight(now = { now }))

    /** How many times the client would have asked for the registry again (the overdue re-request). */
    var reRequests = 0

    /** What the client's scheduled timeout does: an overdue write asks for the list again. */
    fun tick() {
        if (outbox.inFlight.overdue(newest())) reRequests++
    }

    override fun status(): ProvidersWriteStatus = outbox.inFlight.status(newest())
    val writes = mutableListOf<Pair<ProvidersWrite, String>>()
    val refused = mutableListOf<ProvidersRefusal>()

    /** A refusal to answer every write with (as the client would: not connected, no list). */
    var refuseWith: ProvidersRefusal? = null

    private var origin = ""

    /** The frames as sent. */
    fun frames(): List<JsonObject> = writes.map { ProfileFixtures.json(it.first.message.encode()) }

    override fun setProviders(write: ProvidersWrite, origin: String): ProvidersRefusal? {
        refuseWith?.let {
            refused += it
            return it
        }
        this.origin = origin
        val list = newest()
        tick()
        when (val step = outbox.submit(write, list, list?.epoch ?: 0L)) {
            is ProvidersOutbox.Step.Send -> send(step.write, list!!)
            is ProvidersOutbox.Step.Refused -> {
                refused += step.reason
                return step.reason
            }
            ProvidersOutbox.Step.Queued, ProvidersOutbox.Step.NoChange -> Unit
        }
        return null
    }

    /** A `providers` frame was folded (the test set the new list): as the client, send what was queued once the last write is answered. */
    fun landed() {
        val list = newest() ?: return
        outbox.next(list, list.epoch)?.let { send(it, list) }
    }

    private fun send(write: ProvidersWrite, base: ProvidersList) {
        writes += write to origin
        outbox.sent(write, base)
        reply(write)
    }
}
