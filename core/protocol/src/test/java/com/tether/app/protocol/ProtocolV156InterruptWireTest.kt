package com.tether.app.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** tth-fqjc A-stop-core: tether v156 `interrupt.stopBackground`, written only when true (lib/protocol-schema-lu.ts:296). */
class ProtocolV156InterruptWireTest {
    @Test
    fun stopBackgroundIsWrittenOnlyWhenTrue() {
        assertEquals(setOf("type", "sessionId"), ClientMessage.Interrupt("s1").toJsonObject().keys)
        assertEquals(setOf("type", "sessionId"), ClientMessage.Interrupt("s1", stopBackground = false).toJsonObject().keys)
        val on = ClientMessage.Interrupt("s1", stopBackground = true).toJsonObject()
        assertEquals(setOf("type", "sessionId", "stopBackground"), on.keys)
        assertEquals(JsonPrimitive(true), on["stopBackground"])
    }

    @Test
    fun theFrameRoundTripsAndATolerantDecodeReadsOnlyALiteralTrue() {
        for (flag in listOf(false, true)) {
            val message = ClientMessage.Interrupt("s1", flag)
            assertEquals(message, ClientMessage.decode(message.toJsonObject()).getOrThrow())
        }
        val odd = JsonObject(mapOf("type" to JsonPrimitive("interrupt"), "sessionId" to JsonPrimitive("s1"), "stopBackground" to JsonPrimitive("true")))
        assertEquals(ClientMessage.Interrupt("s1", false), ClientMessage.decode(odd).getOrThrow())
    }
}
