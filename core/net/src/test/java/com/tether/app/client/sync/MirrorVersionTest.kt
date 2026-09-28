package com.tether.app.client.sync

import com.tether.app.protocol.PROTOCOL_VERSION
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
        assertEquals("$sha/v$PROTOCOL_VERSION", REDUCER_VERSION)
    }
}
