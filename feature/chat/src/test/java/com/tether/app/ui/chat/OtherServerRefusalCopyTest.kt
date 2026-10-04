package com.tether.app.ui.chat

import com.tether.app.client.BackgroundCommandResult
import com.tether.app.client.ControlResult
import com.tether.app.client.InterruptResult
import com.tether.app.client.RunCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ta-coik.24: the client's NotLive for a session control, an interrupt, a run and a Background now
 * means only "drawn for another server" (RealTetherClient: `expectedOrigin != origin`); the session's
 * liveness gates none of them, as on the web (use-tether.ts 90fbb9f :337-344). So the words say that,
 * never "Catching up … Try again in a moment".
 */
class OtherServerRefusalCopyTest {
    @Test
    fun aKeyDrawnForAnotherServerSaysSo() {
        assertEquals(OTHER_SERVER_NOT_SENT, controlRefusalCopy(ControlResult.NotLive))
        assertEquals(OTHER_SERVER_NOT_SENT, interruptRefusalCopy(InterruptResult.NotLive))
        assertEquals(OTHER_SERVER_NOT_SENT, runRefusalCopy(RunCommandResult.NotLive))
        assertEquals(OTHER_SERVER_NOT_SENT, backgroundRefusalCopy(BackgroundCommandResult.NotLive))
        assertEquals("Nothing was sent: the app is now signed in to another server.", OTHER_SERVER_NOT_SENT)
    }
}
