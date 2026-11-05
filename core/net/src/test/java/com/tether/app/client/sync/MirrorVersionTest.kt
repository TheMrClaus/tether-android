package com.tether.app.client.sync

import com.tether.app.protocol.TARGET_PROTOCOL_VERSION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** T13.1 (SYNC_DESIGN §2.4): the mirror's reducer version follows the vendored corpus. */
class MirrorVersionTest {
    @Test
    fun reducerVersionPinsTheVendoredCorpusAndTheProtocol() {
        val manifest = File(System.getProperty("parity.corpus"), "corpus-manifest.json").readText()
        val sha = Regex("\"tetherSha\"\\s*:\\s*\"([0-9a-f]+)\"").find(manifest)!!.groupValues[1]
        assertEquals("re-synced corpus: bump REDUCER_CORPUS_SHA (it invalidates local checkpoints)", sha, REDUCER_CORPUS_SHA)
        assertEquals("$sha/v$REDUCER_PROTOCOL_VERSION", REDUCER_VERSION)
        // ta-d3tj.10 (R1): the reducer + helpers re-vendored at web 19c56e25 (v152); every fold hunk is ported, so no revision suffix.
        // delivery A4a: re-vendored at tether dc1bc720 (v155, delivery L1): queued_message_delivered, heldReason, queued attachments.
        assertEquals("dc1bc720441ad1f129a7cc92fe5a9513eb2d76ea/v155", REDUCER_VERSION)
        // The hello/advertised protocol stays 143 (ta-d3tj.7 bumps it last); the FOLD is v155's, so older checkpoints re-base.
        assertEquals(155, REDUCER_PROTOCOL_VERSION)
        assertEquals(143, TARGET_PROTOCOL_VERSION)
    }
}
