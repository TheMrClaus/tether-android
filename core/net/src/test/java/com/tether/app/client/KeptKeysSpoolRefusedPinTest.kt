package com.tether.app.client

import com.tether.app.protocol.Attachment
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ta-delivery §4.1d A.1: a record the spool refuses is not kept; one it accepts is. */
class KeptKeysSpoolRefusedPinTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun b64(n: Int) = Base64.getEncoder().encodeToString(ByteArray(n) { (it + 1).toByte() })

    @Test
    fun aSpoolRefusedAttachmentRecordIsNotKeptAndAnAcceptedOneIs() {
        val spool = AttachmentSpool(File(tmp.root, "spool"), maxRecordBytes = 1_500L)
        var store = PendingInput.emptyStore()
        store = PendingInput.addRecord(store, "ok1", PendingInput.KIND_SEND, "s1", "small", 1_000L, listOf(Attachment("a.jpg", "image/jpeg", b64(500)))).store
        store = PendingInput.addRecord(store, "big1", PendingInput.KIND_SEND, "s1", "big", 1_001L, listOf(Attachment("b.jpg", "image/jpeg", b64(5000)))).store
        assertEquals(setOf("ok1"), PendingInput.keptKeys(store, emptyList(), spool))
    }
}
