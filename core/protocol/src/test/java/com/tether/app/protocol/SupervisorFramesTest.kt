package com.tether.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** tether-android#6: the v154 frames and `error` fields, decoded additively and tolerantly. */
class SupervisorFramesTest {

    @Test
    fun supervisorStatusDecodesBothStatesAndIgnoresExtraFields() {
        assertEquals(ServerMessage.SupervisorStatus("down"), ServerMessage.parse("""{"type":"supervisor_status","state":"down"}"""))
        assertEquals(ServerMessage.SupervisorStatus("up"), ServerMessage.parse("""{"type":"supervisor_status","state":"up","bootId":"x"}"""))
    }

    @Test
    fun aStatelessSupervisorStatusIsUnknownNeverAThrow() {
        assertTrue(ServerMessage.parse("""{"type":"supervisor_status"}""") is ServerMessage.Unknown)
    }

    @Test
    fun errorCarriesCodeAndTheKeysWhenPresent() {
        val send = ServerMessage.parse("""{"type":"error","message":"m","requestId":"r","code":"supervisor_down","idempotencyKey":"k1"}""") as ServerMessage.ErrorFrame
        assertEquals("supervisor_down", send.code)
        assertEquals("k1", send.recordKey)
        val queue = ServerMessage.parse("""{"type":"error","message":"m","code":"supervisor_down","queueId":"q1","extra":[1]}""") as ServerMessage.ErrorFrame
        assertEquals("q1", queue.recordKey)
    }

    @Test
    fun anOldServersErrorDecodesAsBefore() {
        val old = ServerMessage.parse("""{"type":"error","message":"boom"}""") as ServerMessage.ErrorFrame
        assertEquals("boom", old.message)
        assertNull(old.code)
        assertNull(old.recordKey)
    }
}
